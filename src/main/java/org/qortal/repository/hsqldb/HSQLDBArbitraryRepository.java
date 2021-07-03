package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.DataType;
import org.qortal.repository.ArbitraryRepository;
import org.qortal.repository.DataException;
import org.qortal.storage.DataFile;

public class HSQLDBArbitraryRepository implements ArbitraryRepository {

	private static final int MAX_RAW_DATA_SIZE = 255; // size of VARBINARY

	protected HSQLDBRepository repository;

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryRepository.class);

	public HSQLDBArbitraryRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	private ArbitraryTransactionData getTransactionData(byte[] signature) throws DataException {
		TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(signature);
		if (transactionData == null)
			return null;

		return (ArbitraryTransactionData) transactionData;
	}

	@Override
	public boolean isDataLocal(byte[] signature) throws DataException {
		ArbitraryTransactionData transactionData = getTransactionData(signature);
		if (transactionData == null) {
			return false;
		}

		// Raw data is always available
		if (transactionData.getDataType() == DataType.RAW_DATA) {
			return true;
		}

		// Load hashes
		byte[] digest = transactionData.getData();
		byte[] chunkHashes = transactionData.getChunkHashes();

		// Load data file(s)
		DataFile dataFile = DataFile.fromDigest(digest);
		if (chunkHashes.length > 0) {
			dataFile.addChunkHashes(chunkHashes);
		}

		// Check if we already have the complete data file
		if (dataFile.exists()) {
			return true;
		}

		// Alternatively, if we have all the chunks, then it's safe to assume the data is local
		if (dataFile.allChunksExist(chunkHashes)) {
			return true;
		}

		return false;
	}

	@Override
	public byte[] fetchData(byte[] signature) throws DataException {
		ArbitraryTransactionData transactionData = getTransactionData(signature);
		if (transactionData == null) {
			return null;
		}

		// Raw data is always available
		if (transactionData.getDataType() == DataType.RAW_DATA) {
			return transactionData.getData();
		}

		// Load hashes
		byte[] digest = transactionData.getData();
		byte[] chunkHashes = transactionData.getChunkHashes();

		// Load data file(s)
		DataFile dataFile = DataFile.fromDigest(digest);
		if (chunkHashes.length > 0) {
			dataFile.addChunkHashes(chunkHashes);
		}

		// If we have the complete data file, return it
		if (dataFile.exists()) {
			return dataFile.getBytes();
		}

		// Alternatively, if we have all the chunks, combine them into a single file
		if (dataFile.allChunksExist(chunkHashes)) {
			dataFile.join();

			// Verify that the combined hash matches the expected hash
			if (digest.equals(dataFile.digest())) {
				return dataFile.getBytes();
			}
		}

		return null;
	}

	@Override
	public void save(ArbitraryTransactionData arbitraryTransactionData) throws DataException {
		// Already hashed? Nothing to do
		if (arbitraryTransactionData.getDataType() == DataType.DATA_HASH) {
			return;
		}

		// Trivial-sized payloads can remain in raw form
		if (arbitraryTransactionData.getDataType() == DataType.RAW_DATA && arbitraryTransactionData.getData().length <= MAX_RAW_DATA_SIZE) {
			return;
		}

		// Store non-trivial payloads in filesystem and convert transaction's data to hash form
		byte[] rawData = arbitraryTransactionData.getData();

		// Calculate hash of data and update our transaction to use that
		byte[] dataHash = Crypto.digest(rawData);
		arbitraryTransactionData.setData(dataHash);
		arbitraryTransactionData.setDataType(DataType.DATA_HASH);

		// Create DataFile
		DataFile dataFile = new DataFile(rawData);

		// Verify that the data file is valid, and that it matches the expected hash
		DataFile.ValidationResult validationResult = dataFile.isValid();
		if (validationResult != DataFile.ValidationResult.OK) {
			dataFile.deleteAll();
			throw new DataException("Invalid data file when attempting to store arbitrary transaction data");
		}
		if (!dataHash.equals(dataFile.digest())) {
			dataFile.deleteAll();
			throw new DataException("Could not verify hash when attempting to store arbitrary transaction data");
		}

		// Now create chunks if needed
		int chunkCount = dataFile.split(DataFile.CHUNK_SIZE);
		if (chunkCount > 0) {
			LOGGER.info(String.format("Successfully split into %d chunk%s:", chunkCount, (chunkCount == 1 ? "" : "s")));
			LOGGER.info("{}", dataFile.printChunks());

			// Verify that the chunk hashes match those in the transaction
			byte[] chunkHashes = dataFile.chunkHashes();
			if (!chunkHashes.equals(arbitraryTransactionData.getChunkHashes())) {
				dataFile.deleteAll();
				throw new DataException("Could not verify chunk hashes when attempting to store arbitrary transaction data");
			}

		}
	}

	@Override
	public void delete(ArbitraryTransactionData arbitraryTransactionData) throws DataException {
		// No need to do anything if we still only have raw data, and hence nothing saved in filesystem
		if (arbitraryTransactionData.getDataType() == DataType.RAW_DATA) {
			return;
		}

		// Load hashes
		byte[] digest = arbitraryTransactionData.getData();
		byte[] chunkHashes = arbitraryTransactionData.getChunkHashes();

		// Load data file(s)
		DataFile dataFile = DataFile.fromDigest(digest);
		if (chunkHashes.length > 0) {
			dataFile.addChunkHashes(chunkHashes);
		}

		// Delete file and chunks
		dataFile.deleteAll();
	}

}
