package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.controller.Controller;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
	private static long STALE_FILE_TIMEOUT = 60*60*1000; // 1 hour


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

		// Use a fixed thread pool to execute the arbitrary data build actions (currently just a single thread)
		// This can be expanded to have multiple threads processing the build queue when needed
		ExecutorService arbitraryDataBuildExecutor = Executors.newFixedThreadPool(1);
		arbitraryDataBuildExecutor.execute(new ArbitraryDataBuildManager());

		// Paginate queries when fetching arbitrary transactions
		final int limit = 100;
		int offset = 0;

		try {
			while (!isStopping) {
				Thread.sleep(30000);

				if (NTP.getTime() == null) {
					// Don't attempt to make decisions if we haven't synced our time yet
					continue;
				}

				List<Peer> peers = Network.getInstance().getHandshakedPeers();

				// Disregard peers that have "misbehaved" recently
				peers.removeIf(Controller.hasMisbehaved);

				// Don't fetch data if we don't have enough up-to-date peers
				if (peers.size() < Settings.getInstance().getMinBlockchainPeers()) {
					continue;
				}

				// Any arbitrary transactions we want to fetch data for?
				try (final Repository repository = RepositoryManager.getRepository()) {
					List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null, ARBITRARY_TX_TYPE, null, null, ConfirmationStatus.BOTH, limit, offset, true);
					// LOGGER.info("Found {} arbitrary transactions at offset: {}, limit: {}", signatures.size(), offset, limit);
					if (signatures == null || signatures.isEmpty()) {
						offset = 0;
						continue;
					}
					offset += limit;
					Long now = NTP.getTime();

					// Loop through the signatures in this batch
					for (int i=0; i<signatures.size(); i++) {
						byte[] signature = signatures.get(i);
						if (signature == null) {
							continue;
						}

						// Fetch the transaction data
						ArbitraryTransactionData arbitraryTransactionData = this.fetchTransactionData(repository, signature);

						// Raw data doesn't have any associated files to clean up
						if (arbitraryTransactionData.getDataType() == ArbitraryTransactionData.DataType.RAW_DATA) {
							continue;
						}

						// Check if we have the complete file
						boolean completeFileExists = this.completeFileExists(arbitraryTransactionData);

						// Check if we have all the chunks
						boolean allChunksExist = this.allChunksExist(arbitraryTransactionData);

						if (completeFileExists && arbitraryTransactionData.getChunkHashes() == null) {
							// This file doesn't have any chunks because it is too small
							// We must not delete anything
							continue;
						}

						if (completeFileExists && allChunksExist) {
							// We have the complete file and all the chunks, so we can delete
							// the complete file if it has reached a certain age.
							LOGGER.info(String.format("Transaction %s has complete file and all chunks",
									Base58.encode(arbitraryTransactionData.getSignature())));

							this.deleteCompleteFile(arbitraryTransactionData, now);
						}

						if (completeFileExists && !allChunksExist) {
							// We have the complete file but not the chunks, so let's convert it
							LOGGER.info(String.format("Transaction %s has complete file but no chunks",
									Base58.encode(arbitraryTransactionData.getSignature())));

							this.createChunks(arbitraryTransactionData, now);
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

	public void shutdown() {
		isStopping = true;
		this.interrupt();
	}


	private ArbitraryTransactionData fetchTransactionData(final Repository repository, final byte[] signature) {
		try {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return null;

			return (ArbitraryTransactionData) transactionData;

		} catch (DataException e) {
			LOGGER.error("Repository issue when fetching arbitrary transaction data", e);
			return null;
		}
	}

	private boolean completeFileExists(ArbitraryTransactionData transactionData) {
		if (transactionData == null) {
			return false;
		}

		byte[] digest = transactionData.getData();

		// Load complete file
		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
		return arbitraryDataFile.exists();

	}

	private boolean allChunksExist(ArbitraryTransactionData transactionData) {
		if (transactionData == null) {
			return false;
		}

		byte[] digest = transactionData.getData();
		byte[] chunkHashes = transactionData.getChunkHashes();

		if (chunkHashes == null) {
			// This file doesn't have any chunks
			return true;
		}

		// Load complete file and chunks
		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
		if (chunkHashes != null && chunkHashes.length > 0) {
			arbitraryDataFile.addChunkHashes(chunkHashes);
		}
		return arbitraryDataFile.allChunksExist(chunkHashes);

	}

	private boolean isFileHashRecent(byte[] hash, long now) {
		try {
			ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash);
			if (arbitraryDataFile == null || !arbitraryDataFile.exists()) {
				// No hash, or file doesn't exist, so it's not recent
				return false;
			}
			Path filePath = arbitraryDataFile.getFilePath();

			BasicFileAttributes attr = Files.readAttributes(filePath, BasicFileAttributes.class);
			long timeSinceCreated = now - attr.creationTime().toMillis();
			long timeSinceModified = now - attr.lastModifiedTime().toMillis();

			// Check if the file has been created or modified recently
			if (timeSinceCreated < STALE_FILE_TIMEOUT) {
				return true;
			}
			if (timeSinceModified < STALE_FILE_TIMEOUT) {
				return true;
			}

		} catch (IOException e) {
			// Can't read file attributes, so assume it's not recent
		}
		return false;
	}

	private void deleteCompleteFile(ArbitraryTransactionData arbitraryTransactionData, long now) {
		byte[] completeHash = arbitraryTransactionData.getData();
		byte[] chunkHashes = arbitraryTransactionData.getChunkHashes();

		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(completeHash);
		arbitraryDataFile.addChunkHashes(chunkHashes);

		if (!this.isFileHashRecent(completeHash, now)) {
			LOGGER.info("Deleting file {} because it can be rebuilt from chunks " +
					"if needed", Base58.encode(completeHash));

			arbitraryDataFile.delete();
		}
	}

	private void createChunks(ArbitraryTransactionData arbitraryTransactionData, long now) {
		byte[] completeHash = arbitraryTransactionData.getData();
		byte[] chunkHashes = arbitraryTransactionData.getChunkHashes();

		// Split the file into chunks
		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(completeHash);
		int chunkCount = arbitraryDataFile.split(ArbitraryDataFile.CHUNK_SIZE);
		if (chunkCount > 1) {
			LOGGER.info(String.format("Successfully split %s into %d chunk%s",
					Base58.encode(completeHash), chunkCount, (chunkCount == 1 ? "" : "s")));

			// Verify that the chunk hashes match those in the transaction
			if (chunkHashes != null && Arrays.equals(chunkHashes, arbitraryDataFile.chunkHashes())) {
				// Ensure they exist on disk
				if (arbitraryDataFile.allChunksExist(chunkHashes)) {

					// Now delete the original file if it's not recent
					if (!this.isFileHashRecent(completeHash, now)) {
						LOGGER.info("Deleting file {} because it can now be rebuilt from " +
								"chunks if needed", Base58.encode(completeHash));

						this.deleteCompleteFile(arbitraryTransactionData, now);
					}
					else {
						// File might be in use. It's best to leave it and it it will be cleaned up later.
					}
				}
			}
		}
	}

}
