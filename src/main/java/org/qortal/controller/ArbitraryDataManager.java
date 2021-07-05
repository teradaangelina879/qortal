package org.qortal.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.storage.DataFile;
import org.qortal.storage.DataFileChunk;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction.TransactionType;

public class ArbitraryDataManager extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataManager.class);
	private static final List<TransactionType> ARBITRARY_TX_TYPE = Arrays.asList(TransactionType.ARBITRARY);

	private static ArbitraryDataManager instance;

	private volatile boolean isStopping = false;

	private ArbitraryDataManager() {
	}

	public static ArbitraryDataManager getInstance() {
		if (instance == null)
			instance = new ArbitraryDataManager();

		return instance;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("Arbitrary Data Manager");

		try {
			while (!isStopping) {
				Thread.sleep(2000);

				// Any arbitrary transactions we want to fetch data for?
				try (final Repository repository = RepositoryManager.getRepository()) {
					List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null, ARBITRARY_TX_TYPE, null, null, ConfirmationStatus.BOTH, null, null, true);
					if (signatures == null || signatures.isEmpty()) {
						continue;
					}

					// Filter out those that already have local data
					signatures.removeIf(signature -> hasLocalData(repository, signature));

					if (signatures.isEmpty()) {
						continue;
					}

					// Pick one at random
					final int index = new Random().nextInt(signatures.size());
					byte[] signature = signatures.get(index);

					// Load the full transaction data so we can access the file hashes
					ArbitraryTransactionData transactionData = (ArbitraryTransactionData)repository.getTransactionRepository().fromSignature(signature);
					if (!(transactionData instanceof ArbitraryTransactionData)) {
						signatures.remove(signature);
						continue;
					}

					// Load hashes
					byte[] digest = transactionData.getData();
					byte[] chunkHashes = transactionData.getChunkHashes();

					// Load data file(s)
					DataFile dataFile = DataFile.fromDigest(digest);
					if (chunkHashes.length > 0) {
						dataFile.addChunkHashes(chunkHashes);

						// Now try and fetch each chunk in turn if we don't have them already
						for (DataFileChunk dataFileChunk : dataFile.getChunks()) {
							if (!dataFileChunk.exists()) {
								LOGGER.info("Requesting chunk {}...", dataFileChunk);
								boolean success = Controller.getInstance().fetchArbitraryDataFile(dataFileChunk.getHash());
								if (success) {
									LOGGER.info("Chunk {} received", dataFileChunk);
								}
								else {
									LOGGER.info("Couldn't retrieve chunk {}", dataFileChunk);
								}
							}
						}
					}
					else if (transactionData.getSize() < DataFileChunk.CHUNK_SIZE) {
						// Fetch the complete file, as it is less than the chunk size
						LOGGER.info("Requesting file {}...", dataFile.getHash58());
						boolean success = Controller.getInstance().fetchArbitraryDataFile(dataFile.getHash());
						if (success) {
							LOGGER.info("File {} received", dataFile);
						}
						else {
							LOGGER.info("Couldn't retrieve file {}", dataFile);
						}
					}
					else {
						// Invalid transaction (should have already failed validation)
						LOGGER.info(String.format("Invalid arbitrary transaction: %.8s", signature));
					}

					signatures.remove(signature);

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

	private boolean hasLocalData(final Repository repository, final byte[] signature) {
		try {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return true;

			ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);

			return arbitraryTransaction.isDataLocal();
		} catch (DataException e) {
			LOGGER.error("Repository issue when checking arbitrary transaction's data is local", e);
			return true;
		}
	}

}
