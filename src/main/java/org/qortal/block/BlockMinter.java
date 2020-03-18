package org.qortal.block;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.block.Block.ValidationResult;
import org.qortal.controller.Controller;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.repository.BlockRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

// Minting new blocks

public class BlockMinter extends Thread {

	// Properties
	private boolean running;

	// Other properties
	private static final Logger LOGGER = LogManager.getLogger(BlockMinter.class);

	// Constructors

	public BlockMinter() {
		this.running = true;
	}

	// Main thread loop
	@Override
	public void run() {
		Thread.currentThread().setName("BlockMinter");

		try (final Repository repository = RepositoryManager.getRepository()) {
			if (Settings.getInstance().getWipeUnconfirmedOnStart()) {
				// Wipe existing unconfirmed transactions
				List<TransactionData> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();

				for (TransactionData transactionData : unconfirmedTransactions) {
					LOGGER.trace(String.format("Deleting unconfirmed transaction %s", Base58.encode(transactionData.getSignature())));
					repository.getTransactionRepository().delete(transactionData);
				}

				repository.saveChanges();
			}

			// Going to need this a lot...
			BlockRepository blockRepository = repository.getBlockRepository();
			Block previousBlock = null;

			List<Block> newBlocks = new ArrayList<>();

			// Flags for tracking change in whether minting is possible,
			// so we can notify Controller, and further update SysTray, etc.
			boolean isMintingPossible = false;
			boolean wasMintingPossible = isMintingPossible;
			while (running) {
				// Sleep for a while
				try {
					repository.discardChanges(); // Free repository locks, if any

					if (isMintingPossible != wasMintingPossible)
						Controller.getInstance().onMintingPossibleChange(isMintingPossible);

					wasMintingPossible = isMintingPossible;

					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// We've been interrupted - time to exit
					return;
				}

				isMintingPossible = false;

				final Long now = NTP.getTime();
				if (now == null)
					continue;

				final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
				if (minLatestBlockTimestamp == null)
					continue;

				// No online accounts? (e.g. during startup)
				if (Controller.getInstance().getOnlineAccounts().isEmpty())
					continue;

				List<MintingAccountData> mintingAccountsData = repository.getAccountRepository().getMintingAccounts();
				// No minting accounts?
				if (mintingAccountsData.isEmpty())
					continue;

				// Disregard minting accounts that are no longer valid, e.g. by transfer/loss of founder flag or account level
				// Note that minting accounts are actually reward-shares in Qortal
				Iterator<MintingAccountData> madi = mintingAccountsData.iterator();
				while (madi.hasNext()) {
					MintingAccountData mintingAccountData = madi.next();

					RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(mintingAccountData.getPublicKey());
					if (rewardShareData == null) {
						// Reward-share doesn't even exist - probably not a good sign
						madi.remove();
						continue;
					}

					PublicKeyAccount mintingAccount = new PublicKeyAccount(repository, rewardShareData.getMinterPublicKey());
					if (!mintingAccount.canMint()) {
						// Minting-account component of reward-share can no longer mint - disregard
						madi.remove();
						continue;
					}
				}

				List<Peer> peers = Network.getInstance().getUniqueHandshakedPeers();
				BlockData lastBlockData = blockRepository.getLastBlock();

				// Disregard peers that have "misbehaved" recently
				peers.removeIf(Controller.hasMisbehaved);

				// Don't mint if we don't have enough connected peers as where would the transactions/consensus come from?
				if (peers.size() < Settings.getInstance().getMinBlockchainPeers())
					continue;

				// Disregard peers that don't have a recent block
				peers.removeIf(Controller.hasNoRecentBlock);

				// If we have any peers with a recent block, but our latest block isn't recent
				// then we need to synchronize instead of minting.
				if (!peers.isEmpty() && lastBlockData.getTimestamp() < minLatestBlockTimestamp)
					continue;

				// There are no peers with a recent block and/or our latest block is recent
				// so go ahead and mint a block if possible.
				isMintingPossible = true;

				// Check blockchain hasn't changed
				if (previousBlock == null || !Arrays.equals(previousBlock.getSignature(), lastBlockData.getSignature())) {
					previousBlock = new Block(repository, lastBlockData);
					newBlocks.clear();
				}

				// Do we need to build any potential new blocks?
				List<PrivateKeyAccount> mintingAccounts = mintingAccountsData.stream().map(accountData -> new PrivateKeyAccount(repository, accountData.getPrivateKey())).collect(Collectors.toList());

				// Discard accounts we have blocks for
				mintingAccounts.removeIf(account -> newBlocks.stream().anyMatch(newBlock -> newBlock.getMinter().getAddress().equals(account.getAddress())));

				for (PrivateKeyAccount mintingAccount : mintingAccounts) {
					// First block does the AT heavy-lifting
					if (newBlocks.isEmpty()) {
						Block newBlock = Block.mint(repository, previousBlock.getBlockData(), mintingAccount);
						newBlocks.add(newBlock);
					} else {
						// The blocks for other minters require less effort...
						Block newBlock = newBlocks.get(0);
						newBlocks.add(newBlock.remint(mintingAccount));
					}
				}

				// No potential block candidates?
				if (newBlocks.isEmpty())
					continue;

				// Make sure we're the only thread modifying the blockchain
				ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
				if (!blockchainLock.tryLock())
					continue;

				boolean newBlockMinted = false;

				try {
					// Clear repository's "in transaction" state so we don't cause a repository deadlock
					repository.discardChanges();

					List<Block> goodBlocks = new ArrayList<>();
					for (Block testBlock : newBlocks) {
						// Is new block's timestamp valid yet?
						// We do a separate check as some timestamp checks are skipped for testchains
						if (testBlock.isTimestampValid() != ValidationResult.OK)
							continue;

						// Is new block valid yet? (Before adding unconfirmed transactions)
						if (testBlock.isValid() != ValidationResult.OK)
							continue;

						goodBlocks.add(testBlock);
					}

					if (goodBlocks.isEmpty())
						continue;

					// Pick best block
					final int parentHeight = previousBlock.getBlockData().getHeight();
					final byte[] parentBlockSignature = previousBlock.getSignature();

					Block newBlock = null;
					BigInteger bestWeight = null;

					for (int bi = 0; bi < goodBlocks.size(); ++bi) {
						BlockData blockData = goodBlocks.get(bi).getBlockData();

						BlockSummaryData blockSummaryData = new BlockSummaryData(blockData);
						int minterLevel = Account.getRewardShareEffectiveMintingLevel(repository, blockData.getMinterPublicKey());
						blockSummaryData.setMinterLevel(minterLevel);

						BigInteger blockWeight = Block.calcBlockWeight(parentHeight, parentBlockSignature, blockSummaryData);

						if (bestWeight == null || blockWeight.compareTo(bestWeight) < 0) {
							newBlock = goodBlocks.get(bi);
							bestWeight = blockWeight;
						}
					}

					// Add unconfirmed transactions
					addUnconfirmedTransactions(repository, newBlock);

					// Sign to create block's signature
					newBlock.sign();

					// Is newBlock still valid?
					ValidationResult validationResult = newBlock.isValid();
					if (validationResult != ValidationResult.OK) {
						// No longer valid? Report and discard
						LOGGER.error(String.format("To-be-minted block now invalid '%s' after adding unconfirmed transactions?", validationResult.name()));

						// Rebuild block candidates, just to be sure
						newBlocks.clear();
						continue;
					}

					// Add to blockchain - something else will notice and broadcast new block to network
					try {
						newBlock.process();

						LOGGER.info(String.format("Minted new block: %d", newBlock.getBlockData().getHeight()));
						repository.saveChanges();

						RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(newBlock.getBlockData().getMinterPublicKey());

						if (rewardShareData != null) {
							PublicKeyAccount mintingAccount = new PublicKeyAccount(repository, rewardShareData.getMinterPublicKey());
							LOGGER.info(String.format("Minted block %d, sig %.8s by %s on behalf of %s",
									newBlock.getBlockData().getHeight(),
									Base58.encode(newBlock.getBlockData().getSignature()),
									mintingAccount.getAddress(),
									rewardShareData.getRecipient()));
						} else {
							LOGGER.info(String.format("Minted block %d, sig %.8s by %s",
									newBlock.getBlockData().getHeight(),
									Base58.encode(newBlock.getBlockData().getSignature()),
									newBlock.getMinter().getAddress()));
						}

						repository.saveChanges();

						// Notify controller
						newBlockMinted = true;
					} catch (DataException e) {
						// Unable to process block - report and discard
						LOGGER.error("Unable to process newly minted block?", e);
						newBlocks.clear();
					}
				} finally {
					blockchainLock.unlock();
				}

				if (newBlockMinted)
					Controller.getInstance().onBlockMinted();
			}
		} catch (DataException e) {
			LOGGER.warn("Repository issue while running block minter", e);
		}
	}

	/**
	 * Adds unconfirmed transactions to passed block.
	 * <p>
	 * NOTE: calls Transaction.getUnconfirmedTransactions which discards uncommitted
	 * repository changes.
	 * 
	 * @param repository
	 * @param newBlock
	 * @throws DataException
	 */
	private static void addUnconfirmedTransactions(Repository repository, Block newBlock) throws DataException {
		// Grab all valid unconfirmed transactions (already sorted)
		List<TransactionData> unconfirmedTransactions = Transaction.getUnconfirmedTransactions(repository);

		Iterator<TransactionData> unconfirmedTransactionsIterator = unconfirmedTransactions.iterator();
		final long newBlockTimestamp = newBlock.getBlockData().getTimestamp();
		while (unconfirmedTransactionsIterator.hasNext()) {
			TransactionData transactionData = unconfirmedTransactionsIterator.next();

			// Ignore transactions that have timestamp later than block's timestamp (not yet valid)
			// Ignore transactions that have expired before this block - they will be cleaned up later
			if (transactionData.getTimestamp() > newBlockTimestamp || Transaction.getDeadline(transactionData) <= newBlockTimestamp)
				unconfirmedTransactionsIterator.remove();
		}

		// Sign to create block's signature, needed by Block.isValid()
		newBlock.sign();

		// Attempt to add transactions until block is full, or we run out
		// If a transaction makes the block invalid then skip it and it'll either expire or be in next block.
		for (TransactionData transactionData : unconfirmedTransactions) {
			if (!newBlock.addTransaction(transactionData))
				break;

			// If newBlock is no longer valid then we can't use transaction
			ValidationResult validationResult = newBlock.isValid();
			if (validationResult != ValidationResult.OK) {
				LOGGER.debug(() -> String.format("Skipping invalid transaction %s during block minting", Base58.encode(transactionData.getSignature())));
				newBlock.deleteTransaction(transactionData);
			}
		}
	}

	public void shutdown() {
		this.running = false;
		// Interrupt too, absorbed by HSQLDB but could be caught by Thread.sleep()
		this.interrupt();
	}

	public static void mintTestingBlock(Repository repository, PrivateKeyAccount... mintingAndOnlineAccounts) throws DataException {
		if (!BlockChain.getInstance().isTestChain()) {
			LOGGER.warn("Ignoring attempt to mint testing block for non-test chain!");
			return;
		}

		// Ensure mintingAccount is 'online' so blocks can be minted
		Controller.getInstance().ensureTestingAccountsOnline(mintingAndOnlineAccounts);

		BlockData previousBlockData = repository.getBlockRepository().getLastBlock();

		PrivateKeyAccount mintingAccount = mintingAndOnlineAccounts[0];

		Block newBlock = Block.mint(repository, previousBlockData, mintingAccount);

		// Make sure we're the only thread modifying the blockchain
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		blockchainLock.lock();
		try {
			// Add unconfirmed transactions
			addUnconfirmedTransactions(repository, newBlock);

			// Sign to create block's signature
			newBlock.sign();

			// Is newBlock still valid?
			ValidationResult validationResult = newBlock.isValid();
			if (validationResult != ValidationResult.OK)
				throw new IllegalStateException(String.format("To-be-minted test block now invalid '%s' after adding unconfirmed transactions?", validationResult.name()));

			// Add to blockchain
			newBlock.process();
			LOGGER.info(String.format("Minted new test block: %d", newBlock.getBlockData().getHeight()));

			repository.saveChanges();
		} finally {
			blockchainLock.unlock();
		}
	}

}
