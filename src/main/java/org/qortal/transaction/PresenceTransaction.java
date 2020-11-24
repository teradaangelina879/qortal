package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.data.transaction.PresenceTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.PresenceTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;

import com.google.common.primitives.Longs;

public class PresenceTransaction extends Transaction {

	// Properties
	private PresenceTransactionData presenceTransactionData;

	// Other useful constants
	public static final int MAX_DATA_SIZE = 256;
	public static final int POW_BUFFER_SIZE = 8 * 1024 * 1024; // bytes
	public static final int POW_DIFFICULTY_WITH_QORT = 8; // leading zero bits
	public static final int POW_DIFFICULTY_NO_QORT = 14; // leading zero bits

	// Constructors

	public PresenceTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.presenceTransactionData = (PresenceTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
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
		PresenceTransactionTransformer.clearNonce(transactionBytes);

		int difficulty = this.getSender().getConfirmedBalance(Asset.QORT) > 0 ? POW_DIFFICULTY_WITH_QORT : POW_DIFFICULTY_NO_QORT;

		// Calculate nonce
		this.presenceTransactionData.setNonce(MemoryPoW.compute2(transactionBytes, POW_BUFFER_SIZE, difficulty));
	}

	/**
	 * Returns whether PRESENCE transaction has valid txGroupId.
	 * <p>
	 * We insist on NO_GROUP.
	 */
	@Override
	protected boolean isValidTxGroupId() throws DataException {
		int txGroupId = this.transactionData.getTxGroupId();

		return txGroupId == Group.NO_GROUP;
	}

	@Override
	public ValidationResult isFeeValid() throws DataException {
		if (this.transactionData.getFee() < 0)
			return ValidationResult.NEGATIVE_FEE;

		return ValidationResult.OK;
	}

	@Override
	public boolean hasValidReference() throws DataException {
		return true;
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Nonce checking is done via isSignatureValid() as that method is only called once per import

		// If we exist in the repository then we've been imported as unconfirmed,
		// but we don't want to make it into a block, so return fake non-OK result.
		if (this.repository.getTransactionRepository().exists(this.presenceTransactionData.getSignature()))
			return ValidationResult.INVALID_BUT_OK;

		// Check timestamp signature
		byte[] timestampSignature = this.presenceTransactionData.getTimestampSignature();
		byte[] timestampBytes = Longs.toByteArray(this.presenceTransactionData.getTimestamp());
		if (!Crypto.verify(this.transactionData.getCreatorPublicKey(), timestampSignature, timestampBytes))
			return ValidationResult.INVALID_TIMESTAMP_SIGNATURE;

		return ValidationResult.OK;
	}

	@Override
	public boolean isSignatureValid() {
		byte[] signature = this.transactionData.getSignature();
		if (signature == null)
			return false;

		byte[] transactionBytes;

		try {
			transactionBytes = PresenceTransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		if (!Crypto.verify(this.transactionData.getCreatorPublicKey(), signature, transactionBytes))
			return false;

		int nonce = this.presenceTransactionData.getNonce();

		// Clear nonce from transactionBytes
		PresenceTransactionTransformer.clearNonce(transactionBytes);

		int difficulty;
		try {
			difficulty = this.getSender().getConfirmedBalance(Asset.QORT) > 0 ? POW_DIFFICULTY_WITH_QORT : POW_DIFFICULTY_NO_QORT;
		} catch (DataException e) {
			return false;
		}

		// Check nonce
		return MemoryPoW.verify2(transactionBytes, POW_BUFFER_SIZE, difficulty, nonce);
	}

	@Override
	public void process() throws DataException {
		throw new DataException("PRESENCE transactions should never be processed");
	}

	@Override
	public void orphan() throws DataException {
		throw new DataException("PRESENCE transactions should never be orphaned");
	}

}
