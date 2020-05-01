package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.ciyam.at.MachineState;
import org.ciyam.at.Timestamp;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.at.AT;
import org.qortal.at.QortalATAPI;
import org.qortal.at.QortalAtLoggerFactory;
import org.qortal.crypto.Crypto;
import org.qortal.data.asset.AssetData;
import org.qortal.data.at.ATData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Amounts;
import org.qortal.transform.transaction.TransactionTransformer;

import com.google.common.base.Utf8;

public class DeployAtTransaction extends Transaction {

	// Properties
	private DeployAtTransactionData deployATTransactionData;

	// Other useful constants
	public static final int MAX_NAME_SIZE = 200;
	public static final int MAX_DESCRIPTION_SIZE = 2000;
	public static final int MAX_AT_TYPE_SIZE = 200;
	public static final int MAX_TAGS_SIZE = 200;
	public static final int MAX_CREATION_BYTES_SIZE = 100_000;

	// Constructors

	public DeployAtTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.deployATTransactionData = (DeployAtTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.deployATTransactionData.getAtAddress());
	}

	/** Returns AT version from the header bytes */
	private short getVersion() {
		byte[] creationBytes = deployATTransactionData.getCreationBytes();
		return (short) ((creationBytes[0] << 8) | (creationBytes[1] & 0xff)); // Big-endian
	}

	/** Make sure deployATTransactionData has an ATAddress */
	private void ensureATAddress() throws DataException {
		if (this.deployATTransactionData.getAtAddress() != null)
			return;

		// Use transaction transformer
		try {
			String atAddress = Crypto.toATAddress(TransactionTransformer.toBytesForSigning(this.deployATTransactionData));
			this.deployATTransactionData.setAtAddress(atAddress);
			return;
		} catch (TransformationException e) {
			throw new DataException("Unable to generate AT address");
		}
	}

	// Navigation

	public Account getATAccount() throws DataException {
		ensureATAddress();

		return new Account(this.repository, this.deployATTransactionData.getAtAddress());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check name size bounds
		int nameLength = Utf8.encodedLength(this.deployATTransactionData.getName());
		if (nameLength < 1 || nameLength > MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check description size bounds
		int descriptionlength = Utf8.encodedLength(this.deployATTransactionData.getDescription());
		if (descriptionlength < 1 || descriptionlength > MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		// Check AT-type size bounds
		int atTypeLength = Utf8.encodedLength(this.deployATTransactionData.getAtType());
		if (atTypeLength < 1 || atTypeLength > MAX_AT_TYPE_SIZE)
			return ValidationResult.INVALID_AT_TYPE_LENGTH;

		// Check tags size bounds
		int tagsLength = Utf8.encodedLength(this.deployATTransactionData.getTags());
		if (tagsLength < 1 || tagsLength > MAX_TAGS_SIZE)
			return ValidationResult.INVALID_TAGS_LENGTH;

		// Check amount is positive
		if (this.deployATTransactionData.getAmount() <= 0)
			return ValidationResult.NEGATIVE_AMOUNT;

		long assetId = this.deployATTransactionData.getAssetId();
		AssetData assetData = this.repository.getAssetRepository().fromAssetId(assetId);
		// Check asset even exists
		if (assetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Unspendable assets are not valid
		if (assetData.getIsUnspendable())
			return ValidationResult.ASSET_NOT_SPENDABLE;

		// Check asset amount is integer if asset is not divisible
		if (!assetData.getIsDivisible() && this.deployATTransactionData.getAmount() % Amounts.MULTIPLIER != 0)
			return ValidationResult.INVALID_AMOUNT;

		Account creator = this.getCreator();

		// Check creator has enough funds
		if (assetId == Asset.QORT) {
			// Simple case: amount and fee both in QORT
			long minimumBalance = this.deployATTransactionData.getFee() + this.deployATTransactionData.getAmount();

			if (creator.getConfirmedBalance(Asset.QORT) < minimumBalance)
				return ValidationResult.NO_BALANCE;
		} else {
			if (creator.getConfirmedBalance(Asset.QORT) < this.deployATTransactionData.getFee())
				return ValidationResult.NO_BALANCE;

			if (creator.getConfirmedBalance(assetId) < this.deployATTransactionData.getAmount())
				return ValidationResult.NO_BALANCE;
		}

		// Check creation bytes are valid (for v2+)
		if (this.getVersion() >= 2) {
			// Do actual validation
			ensureATAddress();

			// Just enough AT data to allow API to query initial balances, etc.
			String atAddress = this.deployATTransactionData.getAtAddress();
			byte[] creatorPublicKey = this.deployATTransactionData.getCreatorPublicKey();
			long creation = this.deployATTransactionData.getTimestamp();
			ATData skeletonAtData = new ATData(atAddress, creatorPublicKey, creation, assetId);

			int height = this.repository.getBlockRepository().getBlockchainHeight() + 1;
			long blockTimestamp = Timestamp.toLong(height, 0);

			QortalATAPI api = new QortalATAPI(repository, skeletonAtData, blockTimestamp);
			QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();

			try {
				new MachineState(api, loggerFactory, this.deployATTransactionData.getCreationBytes());
			} catch (IllegalArgumentException e) {
				// Not valid
				return ValidationResult.INVALID_CREATION_BYTES;
			}
		} else {
			// Skip validation for old, dead ATs
		}

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		Account creator = getCreator();
		long assetId = this.deployATTransactionData.getAssetId();

		// Check creator has enough funds
		if (assetId == Asset.QORT) {
			// Simple case: amount and fee both in QORT
			long minimumBalance = this.deployATTransactionData.getFee() + this.deployATTransactionData.getAmount();

			if (creator.getConfirmedBalance(Asset.QORT) < minimumBalance)
				return ValidationResult.NO_BALANCE;
		} else {
			if (creator.getConfirmedBalance(Asset.QORT) < this.deployATTransactionData.getFee())
				return ValidationResult.NO_BALANCE;

			if (creator.getConfirmedBalance(assetId) < this.deployATTransactionData.getAmount())
				return ValidationResult.NO_BALANCE;
		}

		// Check AT doesn't already exist
		if (this.repository.getATRepository().exists(this.deployATTransactionData.getAtAddress()))
			return ValidationResult.AT_ALREADY_EXISTS;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		this.ensureATAddress();

		// Deploy AT, saving into repository
		AT at = new AT(this.repository, this.deployATTransactionData);
		at.deploy();

		long assetId = this.deployATTransactionData.getAssetId();

		// Update creator's balance regarding initial payment to AT
		Account creator = getCreator();
		creator.setConfirmedBalance(assetId, creator.getConfirmedBalance(assetId) - this.deployATTransactionData.getAmount());

		// Update AT's reference, which also creates AT account
		Account atAccount = this.getATAccount();
		atAccount.setLastReference(this.deployATTransactionData.getSignature());

		// Update AT's balance
		atAccount.setConfirmedBalance(assetId, this.deployATTransactionData.getAmount());
	}

	@Override
	public void orphan() throws DataException {
		// Delete AT from repository
		AT at = new AT(this.repository, this.deployATTransactionData);
		at.undeploy();

		long assetId = this.deployATTransactionData.getAssetId();

		// Update creator's balance regarding initial payment to AT
		Account creator = getCreator();
		creator.setConfirmedBalance(assetId, creator.getConfirmedBalance(assetId) + this.deployATTransactionData.getAmount());

		// Delete AT's account (and hence its balance)
		this.repository.getAccountRepository().delete(this.deployATTransactionData.getAtAddress());
	}

}
