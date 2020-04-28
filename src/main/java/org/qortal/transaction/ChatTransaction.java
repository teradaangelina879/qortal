package org.qortal.transaction;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.ChatTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;

public class ChatTransaction extends Transaction {

	// Properties
	private ChatTransactionData chatTransactionData;

	// Other useful constants
	public static final int MAX_DATA_SIZE = 256;
	public static final int POW_BUFFER_SIZE = 8 * 1024 * 1024; // bytes
	public static final int POW_DIFFICULTY = 12; // leading zero bits

	// Constructors

	public ChatTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.chatTransactionData = (ChatTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() throws DataException {
		return Collections.singletonList(new Account(this.repository, chatTransactionData.getRecipient()));
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getSender().getAddress()))
			return true;

		String recipientAddress = chatTransactionData.getRecipient();
		if (recipientAddress != null && address.equals(recipientAddress))
			return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String senderAddress = this.getSender().getAddress();
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);

		if (address.equals(senderAddress))
			amount = amount.subtract(this.transactionData.getFee());

		return amount;
	}

	// Navigation

	public Account getSender() throws DataException {
		return new PublicKeyAccount(this.repository, this.chatTransactionData.getSenderPublicKey());
	}

	public Account getRecipient() throws DataException {
		String recipientAddress = chatTransactionData.getRecipient();
		if (recipientAddress == null)
			return null;

		return new Account(this.repository, recipientAddress);
	}

	// Processing

	public void computeNonce() {
		byte[] transactionBytes;

		try {
			transactionBytes = TransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		// Clear nonce from transactionBytes
		ChatTransactionTransformer.clearNonce(transactionBytes);

		// Calculate nonce
		this.chatTransactionData.setNonce(MemoryPoW.compute2(transactionBytes, POW_BUFFER_SIZE, POW_DIFFICULTY));
	}

	@Override
	public ValidationResult isFeeValid() throws DataException {
		if (this.transactionData.getFee().compareTo(BigDecimal.ZERO) >= 0)
			return ValidationResult.OK;

		return super.isFeeValid();
	}

	@Override
	public boolean hasValidReference() throws DataException {
		return true;
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// If we exist in the repository then we've been imported as unconfirmed,
		// but we don't want to make it into a block, so return false.
		if (this.repository.getTransactionRepository().exists(this.chatTransactionData.getSignature()))
			return ValidationResult.CHAT;

		// If we have a recipient, check it is a valid address
		String recipientAddress = chatTransactionData.getRecipient();
		if (recipientAddress != null && !Crypto.isValidAddress(recipientAddress))
			return ValidationResult.INVALID_ADDRESS;

		// Check data length
		if (chatTransactionData.getData().length < 1 || chatTransactionData.getData().length > MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// Nonce checking is done via isSignatureValid() as that method is only called once per import

		return ValidationResult.OK;
	}

	@Override
	public boolean isSignatureValid() {
		byte[] signature = this.transactionData.getSignature();
		if (signature == null)
			return false;

		byte[] transactionBytes;

		try {
			transactionBytes = ChatTransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		if (!PublicKeyAccount.verify(this.transactionData.getCreatorPublicKey(), signature, transactionBytes))
			return false;

		int nonce = this.chatTransactionData.getNonce();

		// Clear nonce from transactionBytes
		ChatTransactionTransformer.clearNonce(transactionBytes);

		// Check nonce
		return MemoryPoW.verify2(transactionBytes, POW_BUFFER_SIZE, POW_DIFFICULTY, nonce);
	}

	@Override
	public void process() throws DataException {
		throw new DataException("CHAT transactions should never be processed");
	}

	@Override
	public void orphan() throws DataException {
		throw new DataException("CHAT transactions should never be orphaned");
	}

}
