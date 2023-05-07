package org.qortal.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction;

import java.sql.SQLException;
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

	public static boolean buildArbitraryResourcesCache(Repository repository, boolean forceRebuild) throws DataException {
		if (Settings.getInstance().isLite()) {
			// Lite nodes have no blockchain
			return false;
		}

		try {
			// Check if QDNResources table is empty
			List<ArbitraryResourceData> resources = repository.getArbitraryRepository().getArbitraryResources(10, 0, false);
			if (!resources.isEmpty() && !forceRebuild) {
				// Resources exist in the cache, so assume complete.
				// We avoid checkpointing and prevent the node from starting up in the case of a rebuild failure, so
				// we shouldn't ever be left in a partially rebuilt state.
				LOGGER.debug("Arbitrary resources cache already built");
				return false;
			}

			LOGGER.info("Building arbitrary resources cache...");

			final int batchSize = 100;
			int offset = 0;

			// Loop through all ARBITRARY transactions, and determine latest state
			while (!Controller.isStopping()) {
				LOGGER.info("Fetching arbitrary transactions {} - {}", offset, offset+batchSize-1);

				List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null, List.of(Transaction.TransactionType.ARBITRARY), null, null, null, TransactionsResource.ConfirmationStatus.BOTH, batchSize, offset, false);
				if (signatures.isEmpty()) {
					// Complete
					break;
				}

				// Expand signatures to transactions
				for (byte[] signature : signatures) {
					ArbitraryTransactionData transactionData = (ArbitraryTransactionData) repository
							.getTransactionRepository().fromSignature(signature);

					if (transactionData.getService() == null) {
						// Unsupported service - ignore this resource
						continue;
					}

					// Update arbitrary resource caches
					ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);
					arbitraryTransaction.updateArbitraryResourceCache();
					arbitraryTransaction.updateArbitraryMetadataCache();
				}
				offset += batchSize;
			}

			repository.saveChanges();
			LOGGER.info("Completed build of arbitrary resources cache.");
			return true;
		}
		catch (DataException e) {
			LOGGER.info("Unable to build arbitrary resources cache: {}. The database may have been left in an inconsistent state.", e.getMessage());

			// Throw an exception so that the node startup is halted, allowing for a retry next time.
			repository.discardChanges();
			throw new DataException("Build of arbitrary resources cache failed.");
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
