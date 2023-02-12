package org.qortal.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.transaction.TransactionData;
import org.qortal.gui.SplashFrame;
import org.qortal.repository.hsqldb.HSQLDBDatabaseArchiving;
import org.qortal.repository.hsqldb.HSQLDBDatabasePruning;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;

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

	public static boolean archive(Repository repository) {
		if (Settings.getInstance().isLite()) {
			// Lite nodes have no blockchain
			return false;
		}

		// Bulk archive the database the first time we use archive mode
		if (Settings.getInstance().isArchiveEnabled()) {
			if (RepositoryManager.canArchiveOrPrune()) {
				try {
					return HSQLDBDatabaseArchiving.buildBlockArchive(repository, BlockArchiveWriter.DEFAULT_FILE_SIZE_TARGET);

				} catch (DataException e) {
					LOGGER.info("Unable to build block archive. The database may have been left in an inconsistent state.");
				}
			}
			else {
				LOGGER.info("Unable to build block archive due to missing ATStatesHeightIndex. Bootstrapping is recommended.");
				LOGGER.info("To bootstrap, stop the core and delete the db folder, then start the core again.");
				SplashFrame.getInstance().updateStatus("Missing index. Bootstrapping is recommended.");
			}
		}
		return false;
	}

	public static boolean prune(Repository repository) {
		if (Settings.getInstance().isLite()) {
			// Lite nodes have no blockchain
			return false;
		}

		// Bulk prune the database the first time we use top-only or block archive mode
		if (Settings.getInstance().isTopOnly() ||
			Settings.getInstance().isArchiveEnabled()) {
			if (RepositoryManager.canArchiveOrPrune()) {
				try {
					boolean prunedATStates = HSQLDBDatabasePruning.pruneATStates((HSQLDBRepository) repository);
					boolean prunedBlocks = HSQLDBDatabasePruning.pruneBlocks((HSQLDBRepository) repository);

					// Perform repository maintenance to shrink the db size down
					if (prunedATStates && prunedBlocks) {
						HSQLDBDatabasePruning.performMaintenance(repository);
						return true;
					}

				} catch (SQLException | DataException e) {
					LOGGER.info("Unable to bulk prune AT states. The database may have been left in an inconsistent state.");
				}
			}
			else {
				LOGGER.info("Unable to prune blocks due to missing ATStatesHeightIndex. Bootstrapping is recommended.");
			}
		}
		return false;
	}

	public static boolean rebuildTransactionSequences(Repository repository) throws DataException {
		if (Settings.getInstance().isLite()) {
			// Lite nodes have no blockchain
			return false;
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

			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();
			int totalTransactionCount = 0;

			for (int height = 1; height < blockchainHeight; height++) {
				List<TransactionData> transactions = new ArrayList<>();

				// Fetch transactions for height
				List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, height, height);
				for (byte[] signature : signatures) {
					TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
					if (transactionData != null) {
						transactions.add(transactionData);
					}
				}
				totalTransactionCount += transactions.size();

				// Sort the transactions for this height
				transactions.sort(Transaction.getDataComparator());

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
