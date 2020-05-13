package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
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
		return Collections.emptyList();
	}

	// Navigation

	public Account getOwner() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		String name = this.updateNameTransactionData.getName();

		// Check name size bounds
		int nameLength = Utf8.encodedLength(name);
		if (nameLength < Name.MIN_NAME_SIZE || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

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

		// Check new name (0 length means don't update name)
		String newName = this.updateNameTransactionData.getNewName();
		int newNameLength = Utf8.encodedLength(newName);
		if (newNameLength != 0) {
			// Check new name size bounds
			if (newNameLength < Name.MIN_NAME_SIZE || newNameLength > Name.MAX_NAME_SIZE)
				return ValidationResult.INVALID_NAME_LENGTH;

			// Check new name is lowercase
			if (!newName.equals(newName.toLowerCase()))
				return ValidationResult.NAME_NOT_LOWER_CASE;
		}

		// Check new data size bounds (0 length means don't update data)
		int newDataLength = Utf8.encodedLength(this.updateNameTransactionData.getNewData());
		if (newDataLength > Name.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		Account owner = getOwner();

		// Check owner has enough funds
		if (owner.getConfirmedBalance(Asset.QORT) < this.updateNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		NameData nameData = this.repository.getNameRepository().fromName(this.updateNameTransactionData.getName());

		// Check name still exists
		if (nameData == null)
			return ValidationResult.NAME_DOES_NOT_EXIST;

		// Check name isn't currently for sale
		if (nameData.getIsForSale())
			return ValidationResult.NAME_ALREADY_FOR_SALE;

		Account owner = getOwner();

		// Check transaction's public key matches name's current owner
		if (!owner.getAddress().equals(nameData.getOwner()))
			return ValidationResult.INVALID_NAME_OWNER;

		// Check new name isn't already taken
		if (this.repository.getNameRepository().nameExists(this.updateNameTransactionData.getNewName()))
			return ValidationResult.NAME_ALREADY_REGISTERED;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Update Name
		Name name = new Name(this.repository, this.updateNameTransactionData.getName());
		name.update(this.updateNameTransactionData);

		// Save this transaction, now with updated "name reference" to previous transaction that changed name
		this.repository.getTransactionRepository().save(this.updateNameTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert name

		String nameToRevert = this.updateNameTransactionData.getNewName();
		if (nameToRevert.isEmpty())
			nameToRevert = this.updateNameTransactionData.getName();

		Name name = new Name(this.repository, nameToRevert);
		name.revert(this.updateNameTransactionData);

		// Save this transaction, with previous "name reference"
		this.repository.getTransactionRepository().save(this.updateNameTransactionData);
	}

}
