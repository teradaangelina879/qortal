package org.qora.block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qora.account.PrivateKeyAccount;
import org.qora.account.PublicKeyAccount;
import org.qora.block.Block.ValidationResult;
import org.qora.controller.Controller;
import org.qora.data.account.MintingAccountData;
import org.qora.data.account.RewardShareData;
import org.qora.data.block.BlockData;
import org.qora.data.transaction.TransactionData;
import org.qora.network.Network;
import org.qora.network.Peer;
import org.qora.repository.BlockRepository;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.settings.Settings;
import org.qora.transaction.Transaction;
import org.qora.utils.Base58;
import org.qora.utils.NTP;

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
						Block newBlock = new Block(repository, previousBlock.getBlockData(), mintingAccount);
						newBlocks.add(newBlock);
					} else {
						// The blocks for other minters require less effort...
						Block newBlock = newBlocks.get(0);
						newBlocks.add(newBlock.newMinter(mintingAccount));
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

					// Pick random block
					// TODO/XXX - shouldn't this pick our BEST block instead?
					int winningIndex = new Random().nextInt(goodBlocks.size());
					Block newBlock = goodBlocks.get(winningIndex);

					// Delete invalid transactions. NOTE: discards repository changes on entry, saves changes on exit.
					// deleteInvalidTransactions(repository);

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
	 * Deletes invalid, unconfirmed transactions from repository.
	 * <p>
	 * NOTE: calls Transaction.getInvalidTransactions which discards uncommitted
	 * repository changes.
	 * <p>
	 * Also commits the deletion of invalid transactions to the repository.
	 *  
	 * @param repository
	 * @throws DataException
	 */
	private static void deleteInvalidTransactions(Repository repository) throws DataException {
		List<TransactionData> invalidTransactions = Transaction.getInvalidTransactions(repository);

		// Actually delete invalid transactions from database
		for (TransactionData invalidTransactionData : invalidTransactions) {
			LOGGER.trace(String.format("Deleting invalid, unconfirmed transaction %s", Base58.encode(invalidTransactionData.getSignature())));
			repository.getTransactionRepository().delete(invalidTransactionData);
		}

		repository.saveChanges();
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

	public static void mintTestingBlock(Repository repository, PrivateKeyAccount mintingAccount) throws DataException {
		if (!BlockChain.getInstance().isTestChain()) {
			LOGGER.warn("Ignoring attempt to mint testing block for non-test chain!");
			return;
		}

		// Ensure mintingAccount is 'online' so blocks can be minted
		Controller.getInstance().ensureTestingAccountOnline(mintingAccount);

		BlockData previousBlockData = repository.getBlockRepository().getLastBlock();

		Block newBlock = new Block(repository, previousBlockData, mintingAccount);

		// Make sure we're the only thread modifying the blockchain
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		blockchainLock.lock();
		try {
			// Delete invalid transactions
			// deleteInvalidTransactions(repository);

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
			repository.saveChanges();
		} finally {
			blockchainLock.unlock();
		}
	}

}
