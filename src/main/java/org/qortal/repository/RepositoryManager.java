package org.qortal.repository;

import java.sql.SQLException;

public abstract class RepositoryManager {

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

	public static void backup(boolean quick) {
		try (final Repository repository = getRepository()) {
			repository.backup(quick);
		} catch (DataException e) {
			// Backup is best-effort so don't complain
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

}
