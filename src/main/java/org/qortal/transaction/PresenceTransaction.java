package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.transaction.PresenceTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.PresenceTransactionData.PresenceType;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.PresenceTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Base58;

import com.google.common.primitives.Longs;

public class PresenceTransaction extends Transaction {

	private static final Logger LOGGER = LogManager.getLogger(PresenceTransaction.class);

	// Properties
	private PresenceTransactionData presenceTransactionData;

	// Other useful constants
	public static final int POW_BUFFER_SIZE = 8 * 1024 * 1024; // bytes
	public static final int POW_DIFFICULTY = 8; // leading zero bits

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

		// Calculate nonce
		this.presenceTransactionData.setNonce(MemoryPoW.compute2(transactionBytes, POW_BUFFER_SIZE, POW_DIFFICULTY));
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

		// We only support TRADE_BOT-type PRESENCE at this time
		if (PresenceType.TRADE_BOT != this.presenceTransactionData.getPresenceType())
			return ValidationResult.NOT_YET_RELEASED;

		// Check timestamp signature
		byte[] timestampSignature = this.presenceTransactionData.getTimestampSignature();
		byte[] timestampBytes = Longs.toByteArray(this.presenceTransactionData.getTimestamp());
		if (!Crypto.verify(this.transactionData.getCreatorPublicKey(), timestampSignature, timestampBytes))
			return ValidationResult.INVALID_TIMESTAMP_SIGNATURE;

		// Check signer is known trade address
		String signerAddress = Crypto.toAddress(this.transactionData.getCreatorPublicKey());

		byte[] codeHash = BTCACCT.CODE_BYTES_HASH;
		boolean isExecutable = true;

		List<ATData> atsData = repository.getATRepository().getATsByFunctionality(codeHash, isExecutable, null, null, null);

		for (ATData atData : atsData) {
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.qortalCreatorTradeAddress.equals(signerAddress))
				return ValidationResult.OK;
		}

		return ValidationResult.AT_UNKNOWN;
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

		// Check nonce
		return MemoryPoW.verify2(transactionBytes, POW_BUFFER_SIZE, POW_DIFFICULTY, nonce);
	}

	/**
	 * Remove any PRESENCE transactions by the same signer that have older timestamps.
	 */
	@Override
	protected void onImportAsUnconfirmed() throws DataException {
		byte[] creatorPublicKey = this.transactionData.getCreatorPublicKey();
		List<TransactionData> creatorsPresenceTransactions = this.repository.getTransactionRepository().getUnconfirmedTransactions(TransactionType.PRESENCE, creatorPublicKey);

		if (creatorsPresenceTransactions.isEmpty())
			return;

		// List should contain oldest transaction first, so remove all but last from repository.
		creatorsPresenceTransactions.remove(creatorsPresenceTransactions.size() - 1);
		for (TransactionData transactionData : creatorsPresenceTransactions) {
			LOGGER.info(() -> String.format("Deleting older PRESENCE transaction %s", Base58.encode(transactionData.getSignature())));
			this.repository.getTransactionRepository().delete(transactionData);
		}
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
