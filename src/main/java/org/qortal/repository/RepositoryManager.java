package org.qortal.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.repository.hsqldb.HSQLDBDatabaseArchiving;
import org.qortal.repository.hsqldb.HSQLDBDatabasePruning;
import org.qortal.settings.Settings;

import java.sql.SQLException;

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

	public static void backup(boolean quick, String name) {
		try (final Repository repository = getRepository()) {
			repository.backup(quick, name);
		} catch (DataException e) {
			// Backup is best-effort so don't complain
		}
	}

	public static boolean archive() {
		// Bulk archive the database the first time we use archive mode
		if (Settings.getInstance().isArchiveEnabled()) {
			if (RepositoryManager.canArchiveOrPrune()) {
				try {
					return HSQLDBDatabaseArchiving.buildBlockArchive();

				} catch (DataException e) {
					LOGGER.info("Unable to build block archive. The database may have been left in an inconsistent state.");
				}
			}
			else {
				LOGGER.info("Unable to build block archive due to missing ATStatesHeightIndex. Bootstrapping is recommended.");
			}
		}
		return false;
	}

	public static boolean prune() {
		// Bulk prune the database the first time we use pruning mode
		if (Settings.getInstance().isPruningEnabled() ||
			Settings.getInstance().isArchiveEnabled()) {
			if (RepositoryManager.canArchiveOrPrune()) {
				try {
					boolean prunedATStates = HSQLDBDatabasePruning.pruneATStates();
					boolean prunedBlocks = HSQLDBDatabasePruning.pruneBlocks();

					// Perform repository maintenance to shrink the db size down
					if (prunedATStates && prunedBlocks) {
						HSQLDBDatabasePruning.performMaintenance();
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
