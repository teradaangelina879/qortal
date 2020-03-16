package org.qortal.controller;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.block.Block;
import org.qortal.block.Block.ValidationResult;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.network.PeerChainTipData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Peer;
import org.qortal.network.message.BlockMessage;
import org.qortal.network.message.BlockSummariesMessage;
import org.qortal.network.message.GetBlockMessage;
import org.qortal.network.message.GetBlockSummariesMessage;
import org.qortal.network.message.GetSignaturesMessage;
import org.qortal.network.message.GetSignaturesV2Message;
import org.qortal.network.message.Message;
import org.qortal.network.message.SignaturesMessage;
import org.qortal.network.message.Message.MessageType;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;

public class Synchronizer {

	private static final Logger LOGGER = LogManager.getLogger(Synchronizer.class);

	private static final int INITIAL_BLOCK_STEP = 8;
	private static final int MAXIMUM_BLOCK_STEP = 500;
	private static final int MAXIMUM_COMMON_DELTA = 1440; // XXX move to Settings?
	private static final int SYNC_BATCH_SIZE = 200;

	private static Synchronizer instance;

	public enum SynchronizationResult {
		OK, NOTHING_TO_DO, GENESIS_ONLY, NO_COMMON_BLOCK, TOO_DIVERGENT, NO_REPLY, INFERIOR_CHAIN, INVALID_DATA, NO_BLOCKCHAIN_LOCK, REPOSITORY_ISSUE, SHUTTING_DOWN;
	}

	// Constructors

	private Synchronizer() {
	}

	public static Synchronizer getInstance() {
		if (instance == null)
			instance = new Synchronizer();

		return instance;
	}

	/**
	 * Attempt to synchronize blockchain with peer.
	 * <p>
	 * Will return <tt>true</tt> if synchronization succeeded,
	 * even if no changes were made to our blockchain.
	 * <p>
	 * @param peer
	 * @return false if something went wrong, true otherwise.
	 * @throws InterruptedException
	 */
	public SynchronizationResult synchronize(Peer peer, boolean force) throws InterruptedException {
		// Make sure we're the only thread modifying the blockchain
		// If we're already synchronizing with another peer then this will also return fast
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		if (!blockchainLock.tryLock())
			// Wasn't peer's fault we couldn't sync
			return SynchronizationResult.NO_BLOCKCHAIN_LOCK;

		try {
			try (final Repository repository = RepositoryManager.getRepository()) {
				try {
					final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
					final int ourInitialHeight = ourLatestBlockData.getHeight();

					PeerChainTipData peerChainTipData = peer.getChainTipData();
					int peerHeight = peerChainTipData.getLastHeight();
					byte[] peersLastBlockSignature = peerChainTipData.getLastBlockSignature();

					byte[] ourLastBlockSignature = ourLatestBlockData.getSignature();
					LOGGER.debug(String.format("Synchronizing with peer %s at height %d, sig %.8s, ts %d; our height %d, sig %.8s, ts %d", peer,
							peerHeight, Base58.encode(peersLastBlockSignature), peer.getChainTipData().getLastBlockTimestamp(),
							ourInitialHeight, Base58.encode(ourLastBlockSignature), ourLatestBlockData.getTimestamp()));

					List<BlockSummaryData> peerBlockSummaries = fetchSummariesFromCommonBlock(repository, peer, ourInitialHeight);
					if (peerBlockSummaries == null && Controller.isStopping())
						return SynchronizationResult.SHUTTING_DOWN;

					if (peerBlockSummaries == null) {
						LOGGER.info(String.format("Error while trying to find common block with peer %s", peer));
						return SynchronizationResult.NO_REPLY;
					}
					if (peerBlockSummaries.isEmpty()) {
						LOGGER.info(String.format("Failure to find common block with peer %s", peer));
						return SynchronizationResult.NO_COMMON_BLOCK;
					}

					// First summary is common block
					final BlockData commonBlockData = repository.getBlockRepository().fromSignature(peerBlockSummaries.get(0).getSignature());
					final int commonBlockHeight = commonBlockData.getHeight();
					final byte[] commonBlockSig = commonBlockData.getSignature();
					final String commonBlockSig58 = Base58.encode(commonBlockSig);
					LOGGER.debug(String.format("Common block with peer %s is at height %d, sig %.8s, ts %d", peer,
							commonBlockHeight, commonBlockSig58, commonBlockData.getTimestamp()));
					peerBlockSummaries.remove(0);

					// If common block height is higher than peer's last reported height
					// then peer must have a very recent sync. Update our idea of peer's height.
					if (commonBlockHeight > peerHeight) {
						LOGGER.debug(String.format("Peer height %d was lower than common block height %d - using higher value", peerHeight, commonBlockHeight));
						peerHeight = commonBlockHeight;
					}

					// If common block is peer's latest block then we simply have the same, or longer, chain to peer, so exit now
					if (commonBlockHeight == peerHeight) {
						if (peerHeight == ourInitialHeight)
							LOGGER.debug(String.format("We have the same blockchain as peer %s", peer));
						else
							LOGGER.debug(String.format("We have the same blockchain as peer %s, but longer", peer));

						return SynchronizationResult.NOTHING_TO_DO;
					}

					// If common block is too far behind us then we're on massively different forks so give up.
					int minCommonHeight = ourInitialHeight - MAXIMUM_COMMON_DELTA;
					if (!force && commonBlockHeight < minCommonHeight) {
						LOGGER.info(String.format("Blockchain too divergent with peer %s", peer));
						return SynchronizationResult.TOO_DIVERGENT;
					}

					// Unless we're doing a forced sync, we might need to compare blocks after common block
					if (!force && ourInitialHeight > commonBlockHeight) {
						// If our latest block is very old, we're very behind and should ditch our fork.
						final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
						if (minLatestBlockTimestamp == null)
							return SynchronizationResult.REPOSITORY_ISSUE;

						if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
							LOGGER.info(String.format("Ditching our chain after height %d as our latest block is very old", commonBlockHeight));
						} else {
							// Compare chain weights

							LOGGER.debug(String.format("Comparing chains from block %d with peer %s", commonBlockHeight + 1, peer));

							// Fetch remaining peer's block summaries (which we also use to fill signatures list)
							int peerBlockCount = peerHeight - commonBlockHeight;

							while (peerBlockSummaries.size() < peerBlockCount) {
								if (Controller.isStopping())
									return SynchronizationResult.SHUTTING_DOWN;

								int lastSummaryHeight = commonBlockHeight + peerBlockSummaries.size();
								byte[] previousSignature;
								if (peerBlockSummaries.isEmpty())
									previousSignature = commonBlockSig;
								else
									previousSignature = peerBlockSummaries.get(peerBlockSummaries.size() - 1).getSignature();

								List<BlockSummaryData> moreBlockSummaries = this.getBlockSummaries(peer, previousSignature, peerBlockCount - peerBlockSummaries.size());

								if (moreBlockSummaries == null || moreBlockSummaries.isEmpty()) {
									LOGGER.info(String.format("Peer %s failed to respond with block summaries after height %d, sig %.8s", peer,
											lastSummaryHeight, Base58.encode(previousSignature)));
									return SynchronizationResult.NO_REPLY;
								}

								// Check peer sent valid heights
								for (int i = 0; i < moreBlockSummaries.size(); ++i) {
									++lastSummaryHeight;

									BlockSummaryData blockSummary = moreBlockSummaries.get(i);

									if (blockSummary.getHeight() != lastSummaryHeight) {
										LOGGER.info(String.format("Peer %s responded with invalid block summary for height %d, sig %.8s", peer,
												lastSummaryHeight, Base58.encode(blockSummary.getSignature())));
										return SynchronizationResult.NO_REPLY;
									}
								}

								peerBlockSummaries.addAll(moreBlockSummaries);
							}

							// Fetch our corresponding block summaries
							List<BlockSummaryData> ourBlockSummaries = repository.getBlockRepository().getBlockSummaries(commonBlockHeight + 1, ourInitialHeight);

							// Populate minter account levels for both lists of block summaries
							populateBlockSummariesMinterLevels(repository, peerBlockSummaries);
							populateBlockSummariesMinterLevels(repository, ourBlockSummaries);

							// Calculate cumulative chain weights of both blockchain subsets, from common block to highest mutual block.
							BigInteger ourChainWeight = Block.calcChainWeight(commonBlockHeight, commonBlockSig, ourBlockSummaries);
							BigInteger peerChainWeight = Block.calcChainWeight(commonBlockHeight, commonBlockSig, peerBlockSummaries);

							// If our blockchain has greater weight then don't synchronize with peer
							if (ourChainWeight.compareTo(peerChainWeight) >= 0) {
								LOGGER.debug(String.format("Not synchronizing with peer %s as we have better blockchain", peer));
								NumberFormat formatter = new DecimalFormat("0.###E0");
								LOGGER.debug(String.format("Our chain weight: %s, peer's chain weight: %s (higher is better)", formatter.format(ourChainWeight), formatter.format(peerChainWeight)));
								return SynchronizationResult.INFERIOR_CHAIN;
							}
						}
					}

					int ourHeight = ourInitialHeight;
					if (ourHeight > commonBlockHeight) {
						// Unwind to common block (unless common block is our latest block)
						LOGGER.debug(String.format("Orphaning blocks back to common block height %d, sig %.8s", commonBlockHeight, commonBlockSig58));

						while (ourHeight > commonBlockHeight) {
							if (Controller.isStopping())
								return SynchronizationResult.SHUTTING_DOWN;

							BlockData blockData = repository.getBlockRepository().fromHeight(ourHeight);
							Block block = new Block(repository, blockData);
							block.orphan();

							--ourHeight;
						}

						LOGGER.debug(String.format("Orphaned blocks back to height %d, sig %.8s - fetching blocks from peer %s", commonBlockHeight, commonBlockSig58, peer));
					} else {
						LOGGER.debug(String.format("Fetching new blocks from peer %s", peer));
					}

					// Fetch, and apply, blocks from peer
					byte[] latestPeerSignature = commonBlockSig;
					int maxBatchHeight = commonBlockHeight + SYNC_BATCH_SIZE;

					// Convert any block summaries from above into signatures to request from peer
					List<byte[]> peerBlockSignatures = peerBlockSummaries.stream().map(BlockSummaryData::getSignature).collect(Collectors.toList());

					while (ourHeight < peerHeight && ourHeight < maxBatchHeight) {
						if (Controller.isStopping())
							return SynchronizationResult.SHUTTING_DOWN;

						// Do we need more signatures?
						if (peerBlockSignatures.isEmpty()) {
							int numberRequested = maxBatchHeight - ourHeight;
							LOGGER.trace(String.format("Requesting %d signature%s after height %d, sig %.8s",
									numberRequested, (numberRequested != 1 ? "s": ""), ourHeight, Base58.encode(latestPeerSignature)));

							peerBlockSignatures = this.getBlockSignatures(peer, latestPeerSignature, numberRequested);

							if (peerBlockSignatures == null || peerBlockSignatures.isEmpty()) {
								LOGGER.info(String.format("Peer %s failed to respond with more block signatures after height %d, sig %.8s", peer,
										ourHeight, Base58.encode(latestPeerSignature)));
								return SynchronizationResult.NO_REPLY;
							}

							LOGGER.trace(String.format("Received %s signature%s", peerBlockSignatures.size(), (peerBlockSignatures.size() != 1 ? "s" : "")));
						}

						latestPeerSignature = peerBlockSignatures.get(0);
						peerBlockSignatures.remove(0);
						++ourHeight;

						Block newBlock = this.fetchBlock(repository, peer, latestPeerSignature);

						if (newBlock == null) {
							LOGGER.info(String.format("Peer %s failed to respond with block for height %d, sig %.8s", peer,
									ourHeight, Base58.encode(latestPeerSignature)));
							return SynchronizationResult.NO_REPLY;
						}

						if (!newBlock.isSignatureValid()) {
							LOGGER.info(String.format("Peer %s sent block with invalid signature for height %d, sig %.8s", peer,
									ourHeight, Base58.encode(latestPeerSignature)));
							return SynchronizationResult.INVALID_DATA;
						}

						// Transactions are transmitted without approval status so determine that now
						for (Transaction transaction : newBlock.getTransactions())
							transaction.setInitialApprovalStatus();

						ValidationResult blockResult = newBlock.isValid();
						if (blockResult != ValidationResult.OK) {
							LOGGER.info(String.format("Peer %s sent invalid block for height %d, sig %.8s: %s", peer,
									ourHeight, Base58.encode(latestPeerSignature), blockResult.name()));
							return SynchronizationResult.INVALID_DATA;
						}

						// Save transactions attached to this block
						for (Transaction transaction : newBlock.getTransactions()) {
							TransactionData transactionData = transaction.getTransactionData();
							repository.getTransactionRepository().save(transactionData);
						}

						newBlock.process();

						// If we've grown our blockchain then at least save progress so far
						if (ourHeight > ourInitialHeight)
							repository.saveChanges();
					}

					// Commit
					repository.saveChanges();

					final BlockData newLatestBlockData = repository.getBlockRepository().getLastBlock();
					LOGGER.info(String.format("Synchronized with peer %s to height %d, sig %.8s, ts: %d", peer,
							newLatestBlockData.getHeight(), Base58.encode(newLatestBlockData.getSignature()),
							newLatestBlockData.getTimestamp()));

					return SynchronizationResult.OK;
				} finally {
					repository.discardChanges(); // Free repository locks, if any, also in case anything went wrong
				}
			} catch (DataException e) {
				LOGGER.error("Repository issue during synchronization with peer", e);
				return SynchronizationResult.REPOSITORY_ISSUE;
			}
		} finally {
			blockchainLock.unlock();
		}
	}

	/**
	 * Returns list of peer's block summaries starting with common block with peer.
	 * 
	 * @param peer
	 * @return block summaries, or empty list if no common block, or null if there was an issue
	 * @throws DataException
	 * @throws InterruptedException
	 */
	private List<BlockSummaryData> fetchSummariesFromCommonBlock(Repository repository, Peer peer, int ourHeight) throws DataException, InterruptedException {
		// Start by asking for a few recent block hashes as this will cover a majority of reorgs
		// Failing that, back off exponentially
		int step = INITIAL_BLOCK_STEP;

		List<BlockSummaryData> blockSummaries = null;

		int testHeight = Math.max(ourHeight - step, 1);
		BlockData testBlockData = null;

		while (testHeight >= 1) {
			// Are we shutting down?
			if (Controller.isStopping())
				return null;

			// Fetch our block signature at this height
			testBlockData = repository.getBlockRepository().fromHeight(testHeight);
			if (testBlockData == null) {
				// Not found? But we've locked the blockchain and height is below blockchain's tip!
				LOGGER.error("Failed to get block at height lower than blockchain tip during synchronization?");
				return null;
			}

			// Ask for block signatures since test block's signature
			byte[] testSignature = testBlockData.getSignature();
			LOGGER.trace(String.format("Requesting %d summar%s after height %d", step, (step != 1 ? "ies": "y"), testHeight));
			blockSummaries = this.getBlockSummaries(peer, testSignature, step);

			if (blockSummaries == null)
				// No response - give up this time
				return null;

			LOGGER.trace(String.format("Received %s summar%s", blockSummaries.size(), (blockSummaries.size() != 1 ? "ies" : "y")));

			// Empty list means remote peer is unaware of test signature OR has no new blocks after test signature
			if (!blockSummaries.isEmpty())
				// We have entries so we have found a common block
				break;

			// No blocks after genesis block?
			// We don't get called for a peer at genesis height so this means NO blocks in common
			if (testHeight == 1)
				return Collections.emptyList();

			if (peer.getVersion() >= 2) {
				step <<= 1;
			} else {
				// Old v1 peers are hard-coded to return 500 signatures so we might as well go backward by 500 too
				step = 500;
			}
			step = Math.min(step, MAXIMUM_BLOCK_STEP);

			testHeight = Math.max(testHeight - step, 1);
		}

		// Prepend test block's summary as first block summary, as summaries returned are *after* test block
		BlockSummaryData testBlockSummary = new BlockSummaryData(testBlockData);
		blockSummaries.add(0, testBlockSummary);

		// Trim summaries so that first summary is common block.
		// Currently we work back from the end until we hit a block we also have.
		// TODO: rewrite as modified binary search!
		for (int i = blockSummaries.size() - 1; i > 0; --i) {
			if (repository.getBlockRepository().exists(blockSummaries.get(i).getSignature())) {
				// Note: index i isn't cleared: List.subList is fromIndex inclusive to toIndex exclusive
				blockSummaries.subList(0, i).clear();
				break;
			}
		}

		return blockSummaries;
	}

	private List<BlockSummaryData> getBlockSummaries(Peer peer, byte[] parentSignature, int numberRequested) throws InterruptedException {
		Message getBlockSummariesMessage = new GetBlockSummariesMessage(parentSignature, numberRequested);

		Message message = peer.getResponse(getBlockSummariesMessage);
		if (message == null || message.getType() != MessageType.BLOCK_SUMMARIES)
			return null;

		BlockSummariesMessage blockSummariesMessage = (BlockSummariesMessage) message;

		return blockSummariesMessage.getBlockSummaries();
	}

	private List<byte[]> getBlockSignatures(Peer peer, byte[] parentSignature, int numberRequested) throws InterruptedException {
		// numberRequested is v2+ feature
		Message getSignaturesMessage = peer.getVersion() >= 2 ? new GetSignaturesV2Message(parentSignature, numberRequested) : new GetSignaturesMessage(parentSignature);

		Message message = peer.getResponse(getSignaturesMessage);
		if (message == null || message.getType() != MessageType.SIGNATURES)
			return null;

		SignaturesMessage signaturesMessage = (SignaturesMessage) message;

		return signaturesMessage.getSignatures();
	}

	private Block fetchBlock(Repository repository, Peer peer, byte[] signature) throws InterruptedException {
		Message getBlockMessage = new GetBlockMessage(signature);

		Message message = peer.getResponse(getBlockMessage);
		if (message == null || message.getType() != MessageType.BLOCK)
			return null;

		BlockMessage blockMessage = (BlockMessage) message;

		return new Block(repository, blockMessage.getBlockData(), blockMessage.getTransactions(), blockMessage.getAtStates());
	}

	private void populateBlockSummariesMinterLevels(Repository repository, List<BlockSummaryData> blockSummaries) throws DataException {
		for (int i = 0; i < blockSummaries.size(); ++i) {
			BlockSummaryData blockSummary = blockSummaries.get(i);

			// Qortal: minter is always a reward-share, so find actual minter and get their effective minting level
			int minterLevel = Account.getRewardShareEffectiveMintingLevel(repository, blockSummary.getMinterPublicKey());
			if (minterLevel == 0) {
				// We don't want to throw, or use zero, as this will kill Controller thread and make client unstable.
				// So we log this but use 1 instead
				LOGGER.warn(String.format("Unexpected zero effective minter level for reward-share %s - using 1 instead!", Base58.encode(blockSummary.getMinterPublicKey())));
				minterLevel = 1;
			}

			blockSummary.setMinterLevel(minterLevel);
		}
	}

}
