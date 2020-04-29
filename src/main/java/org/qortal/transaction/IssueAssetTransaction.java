package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.IssueAssetTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import com.google.common.base.Utf8;

public class IssueAssetTransaction extends Transaction {

	// Properties

	private IssueAssetTransactionData issueAssetTransactionData;
	private Account ownerAccount = null;

	// Constructors

	public IssueAssetTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.issueAssetTransactionData = (IssueAssetTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.issueAssetTransactionData.getOwner());
	}

	// Navigation

	public Account getIssuer() {
		return this.getCreator();
	}

	public Account getOwner() {
		if (this.ownerAccount == null)
			this.ownerAccount = new Account(this.repository, this.issueAssetTransactionData.getOwner());

		return this.ownerAccount;
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check owner address is valid
		if (!Crypto.isValidAddress(this.issueAssetTransactionData.getOwner()))
			return ValidationResult.INVALID_ADDRESS;

		// Check name size bounds
		int assetNameLength = Utf8.encodedLength(this.issueAssetTransactionData.getAssetName());
		if (assetNameLength < 1 || assetNameLength > Asset.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check description size bounds
		int assetDescriptionlength = Utf8.encodedLength(this.issueAssetTransactionData.getDescription());
		if (assetDescriptionlength < 1 || assetDescriptionlength > Asset.MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		// Check data field
		String data = this.issueAssetTransactionData.getData();
		int dataLength = Utf8.encodedLength(data);
		if (data == null || dataLength < 1 || dataLength > Asset.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// Check quantity
		if (this.issueAssetTransactionData.getQuantity() < 1 || this.issueAssetTransactionData.getQuantity() > Asset.MAX_QUANTITY)
			return ValidationResult.INVALID_QUANTITY;

		// Check quantity versus indivisibility
		if (!this.issueAssetTransactionData.getIsDivisible() && this.issueAssetTransactionData.getQuantity() % Asset.MULTIPLIER != 0)
			return ValidationResult.INVALID_QUANTITY;

		Account issuer = getIssuer();

		// Check issuer has enough funds
		if (issuer.getConfirmedBalance(Asset.QORT) < this.issueAssetTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Check the asset name isn't already taken.
		if (this.repository.getAssetRepository().assetExists(this.issueAssetTransactionData.getAssetName()))
			return ValidationResult.ASSET_ALREADY_EXISTS;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Issue asset
		Asset asset = new Asset(this.repository, this.issueAssetTransactionData);
		asset.issue();

		// Add asset to owner
		Account owner = getOwner();
		owner.setConfirmedBalance(asset.getAssetData().getAssetId(), this.issueAssetTransactionData.getQuantity());

		// Note newly assigned asset ID in our transaction record
		this.issueAssetTransactionData.setAssetId(asset.getAssetData().getAssetId());

		// Save this transaction with newly assigned assetId
		this.repository.getTransactionRepository().save(this.issueAssetTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Remove asset from owner
		Account owner = getOwner();
		owner.deleteBalance(this.issueAssetTransactionData.getAssetId());

		// Deissue asset
		Asset asset = new Asset(this.repository, this.issueAssetTransactionData.getAssetId());
		asset.deissue();

		// Remove assigned asset ID from transaction info
		this.issueAssetTransactionData.setAssetId(null);

		// Save this transaction, with removed assetId
		this.repository.getTransactionRepository().save(this.issueAssetTransactionData);
	}

}
