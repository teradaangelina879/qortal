package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.controller.Controller;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

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


	/*
	TODO:
	- Discard all files relating to transactions for a name/service combination before the most recent PUT
	- Delete old files from _temp
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
						}

						if (completeFileExists && !allChunksExist) {
							// We have the complete file but not the chunks, so let's convert it
							LOGGER.info(String.format("Transaction %s has complete file but no chunks",
									Base58.encode(arbitraryTransactionData.getSignature())));

							ArbitraryTransactionUtils.convertFileToChunks(arbitraryTransactionData, now, STALE_FILE_TIMEOUT);
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

}
