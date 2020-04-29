package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.payment.Payment;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class MessageTransaction extends Transaction {

	// Properties

	private MessageTransactionData messageTransactionData;
	private PaymentData paymentData = null;

	// Other useful constants
	public static final int MAX_DATA_SIZE = 4000;
	private static final boolean isZeroAmountValid = true;

	// Constructors

	public MessageTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.messageTransactionData = (MessageTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.messageTransactionData.getRecipient());
	}

	// Navigation

	public Account getSender() {
		return this.getCreator();
	}

	public Account getRecipient() {
		return new Account(this.repository, this.messageTransactionData.getRecipient());
	}

	// Processing

	private PaymentData getPaymentData() {
		if (this.paymentData == null)
			this.paymentData = new PaymentData(this.messageTransactionData.getRecipient(), this.messageTransactionData.getAssetId(), this.messageTransactionData.getAmount());

		return this.paymentData;
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Check data length
		if (this.messageTransactionData.getData().length < 1 || this.messageTransactionData.getData().length > MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// Wrap and delegate final payment checks to Payment class
		return new Payment(this.repository).isValid(this.messageTransactionData.getSenderPublicKey(), getPaymentData(), this.messageTransactionData.getFee(),
				isZeroAmountValid);
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Wrap and delegate final processable checks to Payment class
		return new Payment(this.repository).isProcessable(this.messageTransactionData.getSenderPublicKey(), getPaymentData(), this.messageTransactionData.getFee(),
				isZeroAmountValid);
	}

	@Override
	public void process() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).process(this.messageTransactionData.getSenderPublicKey(), getPaymentData(), this.messageTransactionData.getSignature());
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		// Wrap and delegate references processing to Payment class. Only update recipient's last reference if transferring QORT.
		new Payment(this.repository).processReferencesAndFees(this.messageTransactionData.getSenderPublicKey(), getPaymentData(), this.messageTransactionData.getFee(),
				this.messageTransactionData.getSignature(), false);
	}

	@Override
	public void orphan() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).orphan(this.messageTransactionData.getSenderPublicKey(), getPaymentData(), this.messageTransactionData.getSignature(), this.messageTransactionData.getReference());
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// Wrap and delegate references processing to Payment class. Only revert recipient's last reference if transferring QORT.
		new Payment(this.repository).orphanReferencesAndFees(this.messageTransactionData.getSenderPublicKey(), getPaymentData(), this.messageTransactionData.getFee(),
				this.messageTransactionData.getSignature(), this.messageTransactionData.getReference(), false);
	}

}
