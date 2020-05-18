package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.payment.Payment;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class MessageTransaction extends Transaction {

	// Useful constants

	public static final int MAX_DATA_SIZE = 4000;

	// Properties

	private MessageTransactionData messageTransactionData;

	/** Cached, lazy-instantiated payment data. Use {@link #getPaymentData()} instead! */
	private PaymentData paymentData = null;


	// Constructors

	public MessageTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.messageTransactionData = (MessageTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		if (this.messageTransactionData.getRecipient() == null)
			return Collections.emptyList();

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

		// If message has no recipient then it cannot have a payment
		if (this.messageTransactionData.getRecipient() == null && this.messageTransactionData.getAmount() != 0)
			return ValidationResult.INVALID_AMOUNT;

		// If message has no payment then we only need to do a simple balance check for fee
		if (this.messageTransactionData.getAmount() == 0) {
			if (getSender().getConfirmedBalance(Asset.QORT) < this.messageTransactionData.getFee())
				return ValidationResult.NO_BALANCE;

			return ValidationResult.OK;
		}

		// Wrap and delegate final payment checks to Payment class
		return new Payment(this.repository).isValid(this.messageTransactionData.getSenderPublicKey(), getPaymentData(),
				this.messageTransactionData.getFee(), true);
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// If we have no amount then we can always process
		if (this.messageTransactionData.getAmount() == 0L)
			return ValidationResult.OK;

		// Wrap and delegate final processable checks to Payment class
		return new Payment(this.repository).isProcessable(this.messageTransactionData.getSenderPublicKey(),
				getPaymentData(), this.messageTransactionData.getFee(), true);
	}

	@Override
	public void process() throws DataException {
		// If we have no amount then there's nothing to do
		if (this.messageTransactionData.getAmount() == 0L)
			return;

		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).process(this.messageTransactionData.getSenderPublicKey(), getPaymentData());
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		// If we have no amount then we only need to process sender's reference and fees
		if (this.messageTransactionData.getAmount() == 0L) {
			super.processReferencesAndFees();
			return;
		}

		// Wrap and delegate references processing to Payment class. Only update recipient's last reference if transferring QORT.
		new Payment(this.repository).processReferencesAndFees(this.messageTransactionData.getSenderPublicKey(),
				getPaymentData(), this.messageTransactionData.getFee(), this.messageTransactionData.getSignature(),
				false);
	}

	@Override
	public void orphan() throws DataException {
		// If we have no amount then there's nothing to do
		if (this.messageTransactionData.getAmount() == 0L)
			return;

		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).orphan(this.messageTransactionData.getSenderPublicKey(), getPaymentData());
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// If we have no amount then we only need to orphan sender's reference and fees
		if (this.messageTransactionData.getAmount() == 0L) {
			super.orphanReferencesAndFees();
			return;
		}

		// Wrap and delegate references processing to Payment class. Only revert recipient's last reference if transferring QORT.
		new Payment(this.repository).orphanReferencesAndFees(this.messageTransactionData.getSenderPublicKey(),
				getPaymentData(), this.messageTransactionData.getFee(), this.messageTransactionData.getSignature(),
				this.messageTransactionData.getReference(), false);
	}

}
