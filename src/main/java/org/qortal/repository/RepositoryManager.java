package org.qortal.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.Block;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transform.block.BlockTransformation;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

public abstract class RepositoryManager {
	private static final Logger LOGGER = LogManager.getLogger(RepositoryManager.class);

	private static RepositoryFactory repositoryFactory = null;

	/** null if no checkpoint requested, TRUE for quick checkpoint, false for slow/full checkpoint. */
	private static Boolean quickCheckpointRequested = null;

	public static RepositoryFactory getRepositoryFactory() {
		return repositoryFactory;
	}

	public static void setRepositoryFactory(RepositoryFactory newRepositoryFactory) {
		repositoryFactory = newRepositoryFactory;
	}

	public static boolean wasPristineAtOpen() throws DataException {
		if (repositoryFactory == null)
			throw new DataException("No repository available");

		return repositoryFactory.wasPristineAtOpen();
	}

	public static Repository getRepository() throws DataException {
		if (repositoryFactory == null)
			throw new DataException("No repository available");

		return repositoryFactory.getRepository();
	}

	public static Repository tryRepository() throws DataException {
		if (repositoryFactory == null)
			throw new DataException("No repository available");

		return repositoryFactory.tryRepository();
	}

	public static void closeRepositoryFactory() throws DataException {
		repositoryFactory.close();
		repositoryFactory = null;
	}

	public static void backup(boolean quick, String name, Long timeout) throws TimeoutException {
		try (final Repository repository = getRepository()) {
			repository.backup(quick, name, timeout);
		} catch (DataException e) {
			// Backup is best-effort so don't complain
		}
	}
	public static boolean rebuildTransactionSequences(Repository repository) throws DataException {
		if (Settings.getInstance().isLite()) {
			// Lite nodes have no blockchain
			return false;
		}
		if (Settings.getInstance().isTopOnly()) {
			// topOnly nodes are unable to perform this reindex, and so are temporarily unsupported
			throw new DataException("topOnly nodes are now unsupported, as they are missing data required for a db reshape");
		}

		try {
			// Check if we have any unpopulated block_sequence values for the first 1000 blocks
			List<byte[]> testSignatures = repository.getTransactionRepository().getSignaturesMatchingCustomCriteria(
					null, Arrays.asList("block_height < 1000 AND block_sequence IS NULL"), new ArrayList<>());
			if (testSignatures.isEmpty()) {
				// block_sequence already populated for the first 1000 blocks, so assume complete.
				// We avoid checkpointing and prevent the node from starting up in the case of a rebuild failure, so
				// we shouldn't ever be left in a partially rebuilt state.
				return false;
			}

			LOGGER.info("Rebuilding transaction sequences - this will take a while...");

			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();
			int totalTransactionCount = 0;

			for (int height = 1; height < blockchainHeight; height++) {
				List<TransactionData> transactions = new ArrayList<>();

				// Fetch block and transactions
				BlockData blockData = repository.getBlockRepository().fromHeight(height);
				if (blockData == null) {
					// Try the archive
					BlockTransformation blockTransformation = BlockArchiveReader.getInstance().fetchBlockAtHeight(height);
					transactions = blockTransformation.getTransactions();
				}
				else {
					// Get transactions from db
					Block block = new Block(repository, blockData);
					for (Transaction transaction : block.getTransactions()) {
						transactions.add(transaction.getTransactionData());
					}
				}

				totalTransactionCount += transactions.size();

				// Loop through and update sequences
				for (int sequence = 0; sequence < transactions.size(); ++sequence) {
					TransactionData transactionData = transactions.get(sequence);

					// Update transaction's sequence in repository
					repository.getTransactionRepository().updateBlockSequence(transactionData.getSignature(), sequence);
				}

				if (height % 10000 == 0) {
					LOGGER.info("Rebuilt sequences for {} blocks (total transactions: {})", height, totalTransactionCount);
				}

				repository.saveChanges();
			}

			LOGGER.info("Completed rebuild of transaction sequences.");
			return true;
		}
		catch (DataException e) {
			LOGGER.info("Unable to rebuild transaction sequences: {}. The database may have been left in an inconsistent state.", e.getMessage());

			// Throw an exception so that the node startup is halted, allowing for a retry next time.
			repository.discardChanges();
			throw new DataException("Rebuild of transaction sequences failed.");
		}
	}

	public static void setRequestedCheckpoint(Boolean quick) {
		quickCheckpointRequested = quick;
	}

	public static Boolean getRequestedCheckpoint() {
		return quickCheckpointRequested;
	}

	public static void rebuild() throws DataException {
		RepositoryFactory oldRepositoryFactory = repositoryFactory;

		// Grab handle repository reference before we close
		Repository oldRepository = oldRepositoryFactory.getRepository();

		// Use old repository reference to perform rebuild
		oldRepository.rebuild();

		repositoryFactory = oldRepositoryFactory.reopen();
	}

	public static boolean isDeadlockRelated(Throwable e) {
		Throwable cause = e.getCause();

		return SQLException.class.isInstance(cause) && repositoryFactory.isDeadlockException((SQLException) cause);
	}

	public static boolean canArchiveOrPrune() {
		try (final Repository repository = getRepository()) {
			return repository.getATRepository().hasAtStatesHeightIndex();
		} catch (DataException e) {
			return false;
		}
	}

}
