package org.qortal.transaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.asset.Order;
import org.qortal.data.asset.AssetData;
import org.qortal.data.asset.OrderData;
import org.qortal.data.transaction.CreateAssetOrderTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.AssetRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class CreateAssetOrderTransaction extends Transaction {

	// Properties
	private CreateAssetOrderTransactionData createOrderTransactionData;

	// Constructors

	public CreateAssetOrderTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.createOrderTransactionData = (CreateAssetOrderTransactionData) this.transactionData;
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
		return new PublicKeyAccount(this.repository, createOrderTransactionData.getCreatorPublicKey());
	}

	public Order getOrder() throws DataException {
		// orderId is the transaction signature
		OrderData orderData = this.repository.getAssetRepository().fromOrderId(this.createOrderTransactionData.getSignature());
		return new Order(this.repository, orderData);
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		long haveAssetId = createOrderTransactionData.getHaveAssetId();
		long wantAssetId = createOrderTransactionData.getWantAssetId();

		// Check have/want assets are not the same
		if (haveAssetId == wantAssetId)
			return ValidationResult.HAVE_EQUALS_WANT;

		// Check amount is positive
		if (createOrderTransactionData.getAmount().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_AMOUNT;

		// Check price is positive
		if (createOrderTransactionData.getPrice().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_PRICE;

		// Check fee is positive
		if (createOrderTransactionData.getFee().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_FEE;

		AssetRepository assetRepository = this.repository.getAssetRepository();

		// Check "have" asset exists
		AssetData haveAssetData = assetRepository.fromAssetId(haveAssetId);
		if (haveAssetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Check "want" asset exists
		AssetData wantAssetData = assetRepository.fromAssetId(wantAssetId);
		if (wantAssetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Unspendable assets are not tradable
		if (haveAssetData.getIsUnspendable() || wantAssetData.getIsUnspendable())
			return ValidationResult.ASSET_NOT_SPENDABLE;

		Account creator = getCreator();

		BigDecimal committedCost;
		BigDecimal maxOtherAmount;

		/*
		 * "amount" might be either have-asset or want-asset, whichever has the highest assetID.
		 * 
		 * e.g. with assetID 11 "GOLD":
		 * haveAssetId: 0 (QORT), wantAssetId: 11 (GOLD), amount: 123 (GOLD), price: 400 (QORT/GOLD)
		 * stake 49200 QORT, return 123 GOLD
		 * 
		 * haveAssetId: 11 (GOLD), wantAssetId: 0 (QORT), amount: 123 (GOLD), price: 400 (QORT/GOLD)
		 * stake 123 GOLD, return 49200 QORT
		 */
		boolean isAmountWantAsset = haveAssetId < wantAssetId;

		if (isAmountWantAsset) {
			// have/commit 49200 QORT, want/return 123 GOLD
			committedCost = createOrderTransactionData.getAmount().multiply(createOrderTransactionData.getPrice());
			maxOtherAmount = createOrderTransactionData.getAmount();
		} else {
			// have/commit 123 GOLD, want/return 49200 QORT
			committedCost = createOrderTransactionData.getAmount();
			maxOtherAmount = createOrderTransactionData.getAmount().multiply(createOrderTransactionData.getPrice());
		}

		// Check amount is integer if amount's asset is not divisible
		if (!haveAssetData.getIsDivisible() && committedCost.stripTrailingZeros().scale() > 0)
			return ValidationResult.INVALID_AMOUNT;

		// Check total return from fulfilled order would be integer if return's asset is not divisible
		if (!wantAssetData.getIsDivisible() && maxOtherAmount.stripTrailingZeros().scale() > 0)
			return ValidationResult.INVALID_RETURN;

		// Check order creator has enough asset balance AFTER removing fee, in case asset is QORT
		// If asset is QORT then we need to check amount + fee in one go
		if (haveAssetId == Asset.QORT) {
			// Check creator has enough funds for amount + fee in QORT
			if (creator.getConfirmedBalance(Asset.QORT).compareTo(committedCost.add(createOrderTransactionData.getFee())) < 0)
				return ValidationResult.NO_BALANCE;
		} else {
			// Check creator has enough funds for amount in whatever asset
			if (creator.getConfirmedBalance(haveAssetId).compareTo(committedCost) < 0)
				return ValidationResult.NO_BALANCE;

			// Check creator has enough funds for fee in QORT
			if (creator.getConfirmedBalance(Asset.QORT).compareTo(createOrderTransactionData.getFee()) < 0)
				return ValidationResult.NO_BALANCE;
		}

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Order Id is transaction's signature
		byte[] orderId = createOrderTransactionData.getSignature();

		// Process the order itself
		OrderData orderData = new OrderData(orderId, createOrderTransactionData.getCreatorPublicKey(), createOrderTransactionData.getHaveAssetId(),
				createOrderTransactionData.getWantAssetId(), createOrderTransactionData.getAmount(), createOrderTransactionData.getPrice(),
				createOrderTransactionData.getTimestamp());

		new Order(this.repository, orderData).process();
	}

	@Override
	public void orphan() throws DataException {
		// Order Id is transaction's signature
		byte[] orderId = createOrderTransactionData.getSignature();

		// Orphan the order itself
		OrderData orderData = this.repository.getAssetRepository().fromOrderId(orderId);
		new Order(this.repository, orderData).orphan();
	}

}
