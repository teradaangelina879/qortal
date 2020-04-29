package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdateNameTransactionData;
import org.qortal.naming.Name;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import com.google.common.base.Utf8;

public class UpdateNameTransaction extends Transaction {

	// Properties
	private UpdateNameTransactionData updateNameTransactionData;

	// Constructors

	public UpdateNameTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.updateNameTransactionData = (UpdateNameTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.updateNameTransactionData.getNewOwner());
	}

	// Navigation

	public Account getOwner() {
		return this.getCreator();
	}

	public Account getNewOwner() {
		return new Account(this.repository, this.updateNameTransactionData.getNewOwner());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		String name = this.updateNameTransactionData.getName();

		// Check new owner address is valid
		if (!Crypto.isValidAddress(this.updateNameTransactionData.getNewOwner()))
			return ValidationResult.INVALID_ADDRESS;

		// Check name size bounds
		int nameLength = Utf8.encodedLength(name);
		if (nameLength < 1 || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check new data size bounds
		int newDataLength = Utf8.encodedLength(this.updateNameTransactionData.getNewData());
		if (newDataLength < 1 || newDataLength > Name.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// Check name is lowercase
		if (!name.equals(name.toLowerCase()))
			return ValidationResult.NAME_NOT_LOWER_CASE;

		NameData nameData = this.repository.getNameRepository().fromName(name);

		// Check name exists
		if (nameData == null)
			return ValidationResult.NAME_DOES_NOT_EXIST;

		// As this transaction type could require approval, check txGroupId matches groupID at creation
		if (nameData.getCreationGroupId() != this.updateNameTransactionData.getTxGroupId())
			return ValidationResult.TX_GROUP_ID_MISMATCH;

		Account owner = getOwner();

		// Check owner has enough funds
		if (owner.getConfirmedBalance(Asset.QORT) < this.updateNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		NameData nameData = this.repository.getNameRepository().fromName(this.updateNameTransactionData.getName());

		// Check name isn't currently for sale
		if (nameData.getIsForSale())
			return ValidationResult.NAME_ALREADY_FOR_SALE;

		Account owner = getOwner();

		// Check transaction's public key matches name's current owner
		if (!owner.getAddress().equals(nameData.getOwner()))
			return ValidationResult.INVALID_NAME_OWNER;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Update Name
		Name name = new Name(this.repository, this.updateNameTransactionData.getName());
		name.update(this.updateNameTransactionData);

		// Save this transaction, now with updated "name reference" to previous transaction that updated name
		this.repository.getTransactionRepository().save(this.updateNameTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert name
		Name name = new Name(this.repository, this.updateNameTransactionData.getName());
		name.revert(this.updateNameTransactionData);

		// Save this transaction, now with removed "name reference"
		this.repository.getTransactionRepository().save(this.updateNameTransactionData);
	}

}
