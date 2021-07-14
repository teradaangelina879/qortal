package org.qortal.transaction;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;

import org.qortal.account.Account;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.payment.Payment;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.storage.DataFile;
import org.qortal.storage.DataFileChunk;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.ArbitraryTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;

public class ArbitraryTransaction extends Transaction {

	// Properties
	private ArbitraryTransactionData arbitraryTransactionData;

	// Services
	public static final int SERVICE_AUTO_UPDATE = 1;
	public static final int SERVICE_NAME_STORAGE = 10;
	public static final int SERVICE_ARBITRARY_DATA = 100;
	public static final int SERVICE_WEBSITE = 200;
	public static final int SERVICE_GIT_REPOSITORY = 300;
	public static final int SERVICE_BLOG_POST = 777;
	public static final int SERVICE_BLOG_COMMENT = 778;

	// Other useful constants
	public static final int MAX_DATA_SIZE = 4000;
	public static final int MAX_CHUNK_HASHES_LENGTH = 8000;
	public static final int HASH_LENGTH = TransactionTransformer.SHA256_LENGTH;
	public static final int POW_BUFFER_SIZE = 8 * 1024 * 1024; // bytes
	public static final int POW_MIN_DIFFICULTY = 12; // leading zero bits
	public static final int POW_MAX_DIFFICULTY = 19; // leading zero bits
	public static final long MAX_FILE_SIZE = DataFile.MAX_FILE_SIZE;

	// Constructors

	public ArbitraryTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.arbitraryTransactionData = (ArbitraryTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return this.arbitraryTransactionData.getPayments().stream().map(PaymentData::getRecipient).collect(Collectors.toList());
	}

	// Navigation

	public Account getSender() {
		return this.getCreator();
	}

	// Processing

	public void computeNonce() throws DataException {
		byte[] transactionBytes;

		try {
			transactionBytes = TransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		// Clear nonce from transactionBytes
		ArbitraryTransactionTransformer.clearNonce(transactionBytes);

		int difficulty = difficultyForFileSize(arbitraryTransactionData.getSize());

		// Calculate nonce
		this.arbitraryTransactionData.setNonce(MemoryPoW.compute2(transactionBytes, POW_BUFFER_SIZE, difficulty));
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Check that some data - or a data hash - has been supplied
		if (arbitraryTransactionData.getData() == null) {
			return ValidationResult.INVALID_DATA_LENGTH;
		}

		// Check data length
		if (arbitraryTransactionData.getData().length < 1 || arbitraryTransactionData.getData().length > MAX_DATA_SIZE) {
			return ValidationResult.INVALID_DATA_LENGTH;
		}

		// Check hashes
		if (arbitraryTransactionData.getDataType() == ArbitraryTransactionData.DataType.DATA_HASH) {
			// Check length of data hash
			if (arbitraryTransactionData.getData().length != HASH_LENGTH) {
				return ValidationResult.INVALID_DATA_LENGTH;
			}

			// Version 5+
			if (arbitraryTransactionData.getVersion() >= 5) {
				byte[] chunkHashes = arbitraryTransactionData.getChunkHashes();

				// Check maximum length of chunk hashes
				if (chunkHashes != null && chunkHashes.length > MAX_CHUNK_HASHES_LENGTH) {
					return ValidationResult.INVALID_DATA_LENGTH;
				}

				// Check expected length of chunk hashes
				int chunkCount = (int)Math.ceil((double)arbitraryTransactionData.getSize() / (double)DataFileChunk.CHUNK_SIZE);
				int expectedChunkHashesSize = (chunkCount > 1) ? chunkCount * HASH_LENGTH : 0;
				if (chunkHashes == null && expectedChunkHashesSize > 0) {
					return ValidationResult.INVALID_DATA_LENGTH;
				}
				if (chunkHashes.length != expectedChunkHashesSize) {
					return ValidationResult.INVALID_DATA_LENGTH;
				}
			}
		}

		// Check raw data
		if (arbitraryTransactionData.getDataType() == ArbitraryTransactionData.DataType.RAW_DATA) {
			// Version 5+
			if (arbitraryTransactionData.getVersion() >= 5) {
				// Check reported length of the raw data
				// We should not download the raw data, so validation of that will be performed later
				if (arbitraryTransactionData.getSize() > DataFile.MAX_FILE_SIZE) {
					return ValidationResult.INVALID_DATA_LENGTH;
				}
			}
		}

		// Wrap and delegate final payment validity checks to Payment class
		return new Payment(this.repository).isValid(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments(),
				arbitraryTransactionData.getFee());
	}

	@Override
	public boolean isSignatureValid() {
		byte[] signature = this.transactionData.getSignature();
		if (signature == null) {
			return false;
		}

		byte[] transactionBytes;

		try {
			transactionBytes = ArbitraryTransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		if (!Crypto.verify(this.transactionData.getCreatorPublicKey(), signature, transactionBytes)) {
			return false;
		}

		// Nonce wasn't added until version 5+
		if (arbitraryTransactionData.getVersion() >= 5) {

			int nonce = arbitraryTransactionData.getNonce();

			// Clear nonce from transactionBytes
			ArbitraryTransactionTransformer.clearNonce(transactionBytes);

			int difficulty = difficultyForFileSize(arbitraryTransactionData.getSize());

			// Check nonce
			return MemoryPoW.verify2(transactionBytes, POW_BUFFER_SIZE, difficulty, nonce);
		}

		return true;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Wrap and delegate final payment processable checks to Payment class
		return new Payment(this.repository).isProcessable(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments(),
				arbitraryTransactionData.getFee());
	}

	@Override
	public void process() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).process(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments());
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		// Wrap and delegate reference and fee processing to Payment class. Always update recipients' last references regardless of asset.
		new Payment(this.repository).processReferencesAndFees(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments(),
				arbitraryTransactionData.getFee(), arbitraryTransactionData.getSignature(), true);
	}

	@Override
	public void orphan() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).orphan(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments());
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// Wrap and delegate reference and fee processing to Payment class. Always revert recipients' last references regardless of asset.
		new Payment(this.repository).orphanReferencesAndFees(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments(),
				arbitraryTransactionData.getFee(), arbitraryTransactionData.getSignature(), arbitraryTransactionData.getReference(), true);
	}

	// Data access

	public boolean isDataLocal() throws DataException {
		return this.repository.getArbitraryRepository().isDataLocal(this.transactionData.getSignature());
	}

	/** Returns arbitrary data payload, fetching from network if needed. Can block for a while! */
	public byte[] fetchData() throws DataException {
		// If local, read from file
		if (isDataLocal())
			return this.repository.getArbitraryRepository().fetchData(this.transactionData.getSignature());

		// TODO If not local, attempt to fetch via network?
		return null;
	}

	// Helper methods

	public int difficultyForFileSize(long size) {
		final BigInteger powRange = BigInteger.valueOf(POW_MAX_DIFFICULTY - POW_MIN_DIFFICULTY);
		final BigInteger multiplier = BigInteger.valueOf(100);
		final BigInteger percentage = BigInteger.valueOf(size).multiply(multiplier).divide(BigInteger.valueOf(MAX_FILE_SIZE));
		return POW_MIN_DIFFICULTY + powRange.multiply(percentage).divide(multiplier).intValue();
	}

}
