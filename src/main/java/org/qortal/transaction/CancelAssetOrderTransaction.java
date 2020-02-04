package org.qortal.transaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.asset.Order;
import org.qortal.crypto.Crypto;
import org.qortal.data.asset.OrderData;
import org.qortal.data.transaction.CancelAssetOrderTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.AssetRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class CancelAssetOrderTransaction extends Transaction {

	// Properties
	private CancelAssetOrderTransactionData cancelOrderTransactionData;

	// Constructors

	public CancelAssetOrderTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.cancelOrderTransactionData = (CancelAssetOrderTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() {
		return new ArrayList<>();
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		return account.getAddress().equals(this.getCreator().getAddress());
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		BigDecimal amount = BigDecimal.ZERO.setScale(8);

		if (account.getAddress().equals(this.getCreator().getAddress()))
			amount = amount.subtract(this.transactionData.getFee());

		return amount;
	}

	// Navigation

	@Override
	public PublicKeyAccount getCreator() throws DataException {
		return new PublicKeyAccount(this.repository, cancelOrderTransactionData.getCreatorPublicKey());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		AssetRepository assetRepository = this.repository.getAssetRepository();

		// Check fee is positive
		if (cancelOrderTransactionData.getFee().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_FEE;

		// Check order even exists
		OrderData orderData = assetRepository.fromOrderId(cancelOrderTransactionData.getOrderId());

		if (orderData == null)
			return ValidationResult.ORDER_DOES_NOT_EXIST;

		if (orderData.getIsClosed())
			return ValidationResult.ORDER_ALREADY_CLOSED;

		Account creator = getCreator();

		// Check creator's public key results in valid address
		if (!Crypto.isValidAddress(creator.getAddress()))
			return ValidationResult.INVALID_ADDRESS;

		// Check creator's public key matches order's creator's public key
		Account orderCreator = new PublicKeyAccount(this.repository, orderData.getCreatorPublicKey());
		if (!orderCreator.getAddress().equals(creator.getAddress()))
			return ValidationResult.INVALID_ORDER_CREATOR;

		// Check creator has enough QORT for fee
		if (creator.getConfirmedBalance(Asset.QORT).compareTo(cancelOrderTransactionData.getFee()) < 0)
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Mark Order as completed so no more trades can happen
		OrderData orderData = this.repository.getAssetRepository().fromOrderId(cancelOrderTransactionData.getOrderId());
		Order order = new Order(this.repository, orderData);
		order.cancel();
	}

	@Override
	public void orphan() throws DataException {
		// Unmark Order as completed so trades can happen again
		OrderData orderData = this.repository.getAssetRepository().fromOrderId(cancelOrderTransactionData.getOrderId());
		Order order = new Order(this.repository, orderData);
		order.reopen();
	}

}
