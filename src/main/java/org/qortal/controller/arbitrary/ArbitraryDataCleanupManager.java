package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.list.ResourceListManager;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.FilesystemUtils;
import org.qortal.utils.NTP;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ArbitraryDataCleanupManager extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataCleanupManager.class);
	private static final List<TransactionType> ARBITRARY_TX_TYPE = Arrays.asList(TransactionType.ARBITRARY);

	private static ArbitraryDataCleanupManager instance;

	private volatile boolean isStopping = false;

	/**
	 * The amount of time that must pass before a file is treated as stale / not recent.
	 * We can safely delete files created/accessed longer ago that this, if we have a means of
	 * rebuilding them. The main purpose of this is to avoid deleting files that are currently
	 * being used by other parts of the system.
	 */
	private static long STALE_FILE_TIMEOUT = 60*60*1000L; // 1 hour


	/*
	TODO:
	- Delete old files not associated with transactions
	 */


	private ArbitraryDataCleanupManager() {
	}

	public static ArbitraryDataCleanupManager getInstance() {
		if (instance == null)
			instance = new ArbitraryDataCleanupManager();

		return instance;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("Arbitrary Data Cleanup Manager");

		// Keep a reference to the storage manager as we will need this a lot
		ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();

		// Paginate queries when fetching arbitrary transactions
		final int limit = 100;
		int offset = 0;

		try {
			while (!isStopping) {
				Thread.sleep(30000);

				Long now = NTP.getTime();
				if (now == null) {
					// Don't attempt to make decisions if we haven't synced our time yet
					continue;
				}

				// Periodically delete any unnecessary files from the temp directory
				if (offset == 0 || offset % (limit * 10) == 0) {
					this.cleanupTempDirectory(now);
				}

				// Any arbitrary transactions we want to fetch data for?
				try (final Repository repository = RepositoryManager.getRepository()) {
					List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null, ARBITRARY_TX_TYPE, null, null, null, ConfirmationStatus.BOTH, limit, offset, true);
					// LOGGER.info("Found {} arbitrary transactions at offset: {}, limit: {}", signatures.size(), offset, limit);
					if (signatures == null || signatures.isEmpty()) {
						offset = 0;
						continue;
					}
					offset += limit;
					now = NTP.getTime();

					// Loop through the signatures in this batch
					for (int i=0; i<signatures.size(); i++) {
						byte[] signature = signatures.get(i);
						if (signature == null) {
							continue;
						}

						// Don't interfere with the filesystem whilst a build is in progress
						if (ArbitraryDataBuildManager.getInstance().getBuildInProgress()) {
							Thread.sleep(5000);
						}

						// Fetch the transaction data
						ArbitraryTransactionData arbitraryTransactionData = ArbitraryTransactionUtils.fetchTransactionData(repository, signature);

						// Raw data doesn't have any associated files to clean up
						if (arbitraryTransactionData.getDataType() == ArbitraryTransactionData.DataType.RAW_DATA) {
							continue;
						}

						// Check if we have the complete file
						boolean completeFileExists = ArbitraryTransactionUtils.completeFileExists(arbitraryTransactionData);

						// Check if we have any of the chunks
						boolean anyChunksExist = ArbitraryTransactionUtils.anyChunksExist(arbitraryTransactionData);
						boolean transactionHasChunks = (arbitraryTransactionData.getChunkHashes() != null);

						if (!completeFileExists && !anyChunksExist) {
							// We don't have any files at all for this transaction - nothing to do
							continue;
						}

						// We have at least 1 chunk or file for this transaction, so we might need to delete them...


						// Check to see if we should be hosting data for this transaction at all
						if (!storageManager.canStoreDataForName(arbitraryTransactionData.getName())) {
							LOGGER.info("Deleting transaction {} because we can't host data for name {}",
									Base58.encode(arbitraryTransactionData.getSignature()), arbitraryTransactionData.getName());
							ArbitraryTransactionUtils.deleteCompleteFileAndChunks(arbitraryTransactionData);
							continue;
						}

						// Check to see if we have had a more recent PUT
						boolean hasMoreRecentPutTransaction = ArbitraryTransactionUtils.hasMoreRecentPutTransaction(repository, arbitraryTransactionData);
						if (hasMoreRecentPutTransaction) {
							// There is a more recent PUT transaction than the one we are currently processing.
							// When a PUT is issued, it replaces any layers that would have been there before.
							// Therefore any data relating to this older transaction is no longer needed.
							LOGGER.info(String.format("Newer PUT found for %s %s since transaction %s. " +
											"Deleting all files.", arbitraryTransactionData.getService(),
									arbitraryTransactionData.getName(), Base58.encode(signature)));

							ArbitraryTransactionUtils.deleteCompleteFileAndChunks(arbitraryTransactionData);
							continue;
						}

						if (completeFileExists && !transactionHasChunks) {
							// This file doesn't have any chunks because it is too small.
							// We must not delete anything.
							continue;
						}

						// Check if we have all of the chunks
						boolean allChunksExist = ArbitraryTransactionUtils.allChunksExist(arbitraryTransactionData);

						if (completeFileExists && allChunksExist) {
							// We have the complete file and all the chunks, so we can delete
							// the complete file if it has reached a certain age.
							LOGGER.info(String.format("Transaction %s has complete file and all chunks",
									Base58.encode(arbitraryTransactionData.getSignature())));

							ArbitraryTransactionUtils.deleteCompleteFile(arbitraryTransactionData, now, STALE_FILE_TIMEOUT);
							continue;
						}

						if (completeFileExists && !allChunksExist) {
							// We have the complete file but not the chunks, so let's convert it
							LOGGER.info(String.format("Transaction %s has complete file but no chunks",
									Base58.encode(arbitraryTransactionData.getSignature())));

							ArbitraryTransactionUtils.convertFileToChunks(arbitraryTransactionData, now, STALE_FILE_TIMEOUT);
							continue;
						}
					}

				} catch (DataException e) {
					LOGGER.error("Repository issue when fetching arbitrary transaction data", e);
				}
			}
		} catch (InterruptedException e) {
			// Fall-through to exit thread...
		}
	}

	private void cleanupTempDirectory(String folder, long now, long minAge) {
		String baseDir = Settings.getInstance().getTempDataPath();
		Path tempDir = Paths.get(baseDir, folder);
		int contentsCount = 0;

		// Loop through the contents and check each one
		final File[] directories = tempDir.toFile().listFiles();
		if (directories != null) {
			for (final File directory : directories) {
				contentsCount++;

				// We're expecting the contents of each subfolder to be a directory
				if (directory.isDirectory()) {
					if (!ArbitraryTransactionUtils.isFileRecent(directory.toPath(), now, minAge)) {
						// File isn't recent, so can be deleted
						this.safeDeleteDirectory(directory, "not recent");
					}
				}
			}
		}

		// If the directory is empty, we still need to delete its parent folder
		if (contentsCount == 0 && tempDir.toFile().exists()) {
			try {
				LOGGER.info("Parent directory {} is empty, so deleting it", tempDir);
				FilesystemUtils.safeDeleteDirectory(tempDir, false);
			} catch(IOException e){
				LOGGER.info("Unable to delete parent directory: {}", tempDir);
			}
		}
	}

	private void cleanupReaderCache(Long now) {
		ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();
		String baseDir = Settings.getInstance().getTempDataPath();
		Path readerCachePath = Paths.get(baseDir, "reader");

		// Clean up names
		Path readerCacheNamesPath = Paths.get(readerCachePath.toString(), "NAME");

		// Loop through the contents and check each one
		final File[] directories = readerCacheNamesPath.toFile().listFiles();
		if (directories != null) {
			for (final File directory : directories) {

				// Delete data relating to blacklisted names
				String name = directory.getName();
				if (name != null && storageManager.isNameInBlacklist(name)) {
					this.safeDeleteDirectory(directory, "blacklisted name");
				}

				// Delete cached reader data that has reached its expiry
				this.cleanupReaderCacheForName(name, now);
			}
		}
	}

	private void cleanupReaderCacheForName(String name, Long now) {
		if (name == null) {
			return;
		}

		String baseDir = Settings.getInstance().getTempDataPath();
		Path readerNameCachePath = Paths.get(baseDir, "reader", "NAME", name);

		// Loop through the contents and check each one
		final File[] directories = readerNameCachePath.toFile().listFiles();
		if (directories != null) {
			for (final File directory : directories) {
				// Each directory is a "service" type
				String service = directory.getName();
				this.cleanupReaderCacheForNameAndService(name, service, now);
			}
		}
	}

	private void cleanupReaderCacheForNameAndService(String name, String service, Long now) {
		if (name == null || service == null) {
			return;
		}

		Path readerNameServiceCachePath = Paths.get("reader", "NAME", name, service);
		Long expiry = Settings.getInstance().getBuiltDataExpiryInterval();
		this.cleanupTempDirectory(readerNameServiceCachePath.toString(), now, expiry);
	}

	private void cleanupTempDirectory(long now) {

		// Use the "stale file timeout" for the intermediate directories.
		// These aren't used for serving content - only for building it.
		// Once the files have become stale, it's safe to delete them.
		this.cleanupTempDirectory("diff",  now, STALE_FILE_TIMEOUT);
		this.cleanupTempDirectory("join",  now, STALE_FILE_TIMEOUT);
		this.cleanupTempDirectory("merge",  now, STALE_FILE_TIMEOUT);
		this.cleanupTempDirectory("writer",  now, STALE_FILE_TIMEOUT);

		// Built resources are served out of the "reader" directory so these
		// need to be kept around for much longer.
		// Purging currently disabled, as it's not very helpful. Will revisit
		// once we implement local storage limits.
		this.cleanupReaderCache(now);

	}

	private boolean safeDeleteDirectory(File directory, String reason) {
		LOGGER.info("Deleting directory {} due to reason: {}", directory, reason);
		try {
			FilesystemUtils.safeDeleteDirectory(directory.toPath(), true);
			return true;
		} catch (IOException e) {
			LOGGER.info("Unable to delete directory: {}", directory);
		}
		return false;
	}


	public void shutdown() {
		isStopping = true;
		this.interrupt();
	}

}
