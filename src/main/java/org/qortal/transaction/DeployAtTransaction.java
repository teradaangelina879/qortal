package org.qortal.transaction;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.ciyam.at.MachineState;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.at.AT;
import org.qortal.crypto.Crypto;
import org.qortal.data.asset.AssetData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transform.Transformer;

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
	public List<Account> getRecipientAccounts() throws DataException {
		return new ArrayList<>();
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getCreator().getAddress()))
			return true;

		if (address.equals(this.getATAccount().getAddress()))
			return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);

		if (address.equals(this.getCreator().getAddress()))
			amount = amount.subtract(this.deployATTransactionData.getAmount()).subtract(this.transactionData.getFee());

		if (address.equals(this.getATAccount().getAddress()))
			amount = amount.add(this.deployATTransactionData.getAmount());

		return amount;
	}

	/** Returns AT version from the header bytes */
	private short getVersion() {
		byte[] creationBytes = deployATTransactionData.getCreationBytes();
		return (short) ((creationBytes[0] & 0xff) | (creationBytes[1] << 8)); // Little-endian
	}

	/** Make sure deployATTransactionData has an ATAddress */
	private void ensureATAddress() throws DataException {
		if (this.deployATTransactionData.getAtAddress() != null)
			return;

		int blockHeight = this.getHeight();
		if (blockHeight == 0)
			blockHeight = this.repository.getBlockRepository().getBlockchainHeight() + 1;

		byte[] name = this.deployATTransactionData.getName().getBytes(StandardCharsets.UTF_8);
		byte[] description = this.deployATTransactionData.getDescription().replaceAll("\\s", "").getBytes(StandardCharsets.UTF_8);
		byte[] creatorPublicKey = this.deployATTransactionData.getCreatorPublicKey();
		byte[] creationBytes = this.deployATTransactionData.getCreationBytes();

		ByteBuffer byteBuffer = ByteBuffer
				.allocate(name.length + description.length + creatorPublicKey.length + creationBytes.length + Transformer.INT_LENGTH);

		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

		byteBuffer.put(name);
		byteBuffer.put(description);
		byteBuffer.put(creatorPublicKey);
		byteBuffer.put(creationBytes);
		byteBuffer.putInt(blockHeight);

		String atAddress = Crypto.toATAddress(byteBuffer.array());

		this.deployATTransactionData.setAtAddress(atAddress);
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
		int nameLength = Utf8.encodedLength(deployATTransactionData.getName());
		if (nameLength < 1 || nameLength > MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check description size bounds
		int descriptionlength = Utf8.encodedLength(deployATTransactionData.getDescription());
		if (descriptionlength < 1 || descriptionlength > MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		// Check AT-type size bounds
		int ATTypeLength = Utf8.encodedLength(deployATTransactionData.getAtType());
		if (ATTypeLength < 1 || ATTypeLength > MAX_AT_TYPE_SIZE)
			return ValidationResult.INVALID_AT_TYPE_LENGTH;

		// Check tags size bounds
		int tagsLength = Utf8.encodedLength(deployATTransactionData.getTags());
		if (tagsLength < 1 || tagsLength > MAX_TAGS_SIZE)
			return ValidationResult.INVALID_TAGS_LENGTH;

		// Check amount is positive
		if (deployATTransactionData.getAmount().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_AMOUNT;

		long assetId = deployATTransactionData.getAssetId();
		AssetData assetData = this.repository.getAssetRepository().fromAssetId(assetId);
		// Check asset even exists
		if (assetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Unspendable assets are not valid
		if (assetData.getIsUnspendable())
			return ValidationResult.ASSET_NOT_SPENDABLE;

		// Check asset amount is integer if asset is not divisible
		if (!assetData.getIsDivisible() && deployATTransactionData.getAmount().stripTrailingZeros().scale() > 0)
			return ValidationResult.INVALID_AMOUNT;

		// Check fee is positive
		if (deployATTransactionData.getFee().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_FEE;

		Account creator = getCreator();

		// Check creator has enough funds
		if (assetId == Asset.QORT) {
			// Simple case: amount and fee both in QORT
			BigDecimal minimumBalance = deployATTransactionData.getFee().add(deployATTransactionData.getAmount());

			if (creator.getConfirmedBalance(Asset.QORT).compareTo(minimumBalance) < 0)
				return ValidationResult.NO_BALANCE;
		} else {
			if (creator.getConfirmedBalance(Asset.QORT).compareTo(deployATTransactionData.getFee()) < 0)
				return ValidationResult.NO_BALANCE;

			if (creator.getConfirmedBalance(assetId).compareTo(deployATTransactionData.getAmount()) < 0)
				return ValidationResult.NO_BALANCE;
		}

		// Check creation bytes are valid (for v2+)
		if (this.getVersion() >= 2) {
			// Do actual validation
			try {
				new MachineState(deployATTransactionData.getCreationBytes());
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
		long assetId = deployATTransactionData.getAssetId();

		// Check creator has enough funds
		if (assetId == Asset.QORT) {
			// Simple case: amount and fee both in QORT
			BigDecimal minimumBalance = deployATTransactionData.getFee().add(deployATTransactionData.getAmount());

			if (creator.getConfirmedBalance(Asset.QORT).compareTo(minimumBalance) < 0)
				return ValidationResult.NO_BALANCE;
		} else {
			if (creator.getConfirmedBalance(Asset.QORT).compareTo(deployATTransactionData.getFee()) < 0)
				return ValidationResult.NO_BALANCE;

			if (creator.getConfirmedBalance(assetId).compareTo(deployATTransactionData.getAmount()) < 0)
				return ValidationResult.NO_BALANCE;
		}

		// Check AT doesn't already exist
		if (this.repository.getATRepository().exists(deployATTransactionData.getAtAddress()))
			return ValidationResult.AT_ALREADY_EXISTS;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		ensureATAddress();

		// Deploy AT, saving into repository
		AT at = new AT(this.repository, this.deployATTransactionData);
		at.deploy();

		long assetId = deployATTransactionData.getAssetId();

		// Update creator's balance regarding initial payment to AT
		Account creator = getCreator();
		creator.setConfirmedBalance(assetId, creator.getConfirmedBalance(assetId).subtract(deployATTransactionData.getAmount()));

		// Update AT's reference, which also creates AT account
		Account atAccount = this.getATAccount();
		atAccount.setLastReference(deployATTransactionData.getSignature());

		// Update AT's balance
		atAccount.setConfirmedBalance(assetId, deployATTransactionData.getAmount());
	}

	@Override
	public void orphan() throws DataException {
		// Delete AT from repository
		AT at = new AT(this.repository, this.deployATTransactionData);
		at.undeploy();

		long assetId = deployATTransactionData.getAssetId();

		// Update creator's balance regarding initial payment to AT
		Account creator = getCreator();
		creator.setConfirmedBalance(assetId, creator.getConfirmedBalance(assetId).add(deployATTransactionData.getAmount()));

		// Delete AT's account (and hence its balance)
		this.repository.getAccountRepository().delete(this.deployATTransactionData.getAtAddress());
	}

}
