package org.qortal.transaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.MultiPaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.payment.Payment;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class MultiPaymentTransaction extends Transaction {

	// Properties
	private MultiPaymentTransactionData multiPaymentTransactionData;

	// Useful constants
	private static final int MAX_PAYMENTS_COUNT = 400;

	// Constructors

	public MultiPaymentTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.multiPaymentTransactionData = (MultiPaymentTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() throws DataException {
		List<Account> recipients = new ArrayList<>();

		for (PaymentData paymentData : multiPaymentTransactionData.getPayments())
			recipients.add(new Account(this.repository, paymentData.getRecipient()));

		return recipients;
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getSender().getAddress()))
			return true;

		for (PaymentData paymentData : multiPaymentTransactionData.getPayments())
			if (address.equals(paymentData.getRecipient()))
				return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);
		String senderAddress = this.getSender().getAddress();

		if (address.equals(senderAddress))
			amount = amount.subtract(this.transactionData.getFee());

		// We're only interested in QORT
		for (PaymentData paymentData : multiPaymentTransactionData.getPayments())
			if (paymentData.getAssetId() == Asset.QORT) {
				if (address.equals(paymentData.getRecipient()))
					amount = amount.add(paymentData.getAmount());
				else if (address.equals(senderAddress))
					amount = amount.subtract(paymentData.getAmount());
			}

		return amount;
	}

	// Navigation

	public Account getSender() throws DataException {
		return new PublicKeyAccount(this.repository, this.multiPaymentTransactionData.getSenderPublicKey());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		List<PaymentData> payments = multiPaymentTransactionData.getPayments();

		// Check number of payments
		if (payments.isEmpty() || payments.size() > MAX_PAYMENTS_COUNT)
			return ValidationResult.INVALID_PAYMENTS_COUNT;

		// Check reference is correct
		Account sender = getSender();

		// Check sender has enough funds for fee
		if (sender.getConfirmedBalance(Asset.QORT).compareTo(multiPaymentTransactionData.getFee()) < 0)
			return ValidationResult.NO_BALANCE;

		return new Payment(this.repository).isValid(multiPaymentTransactionData.getSenderPublicKey(), payments, multiPaymentTransactionData.getFee());
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		List<PaymentData> payments = multiPaymentTransactionData.getPayments();

		return new Payment(this.repository).isProcessable(multiPaymentTransactionData.getSenderPublicKey(), payments, multiPaymentTransactionData.getFee());
	}

	@Override
	public void process() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).process(multiPaymentTransactionData.getSenderPublicKey(), multiPaymentTransactionData.getPayments(),
				multiPaymentTransactionData.getFee(), multiPaymentTransactionData.getSignature());
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		// Wrap and delegate reference processing to Payment class. Always update recipients' last references regardless of asset.
		new Payment(this.repository).processReferencesAndFees(multiPaymentTransactionData.getSenderPublicKey(), multiPaymentTransactionData.getPayments(),
				multiPaymentTransactionData.getFee(), multiPaymentTransactionData.getSignature(), true);
	}

	@Override
	public void orphan() throws DataException {
		// Wrap and delegate payment processing to Payment class. Always revert recipients' last references regardless of asset.
		new Payment(this.repository).orphan(multiPaymentTransactionData.getSenderPublicKey(), multiPaymentTransactionData.getPayments(),
				multiPaymentTransactionData.getFee(), multiPaymentTransactionData.getSignature(), multiPaymentTransactionData.getReference());
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// Wrap and delegate reference processing to Payment class. Always revert recipients' last references regardless of asset.
		new Payment(this.repository).orphanReferencesAndFees(multiPaymentTransactionData.getSenderPublicKey(), multiPaymentTransactionData.getPayments(),
				multiPaymentTransactionData.getFee(), multiPaymentTransactionData.getSignature(), multiPaymentTransactionData.getReference(), true);
	}

}
