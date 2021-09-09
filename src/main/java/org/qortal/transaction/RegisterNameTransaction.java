package org.qortal.transaction;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.naming.Name;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Unicode;

import com.google.common.base.Utf8;

public class RegisterNameTransaction extends Transaction {

	// Properties
	private RegisterNameTransactionData registerNameTransactionData;

	// Constructors

	public RegisterNameTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.registerNameTransactionData = (RegisterNameTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getRegistrant() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		Account registrant = getRegistrant();
		String name = this.registerNameTransactionData.getName();

		// Check name size bounds
		int nameLength = Utf8.encodedLength(name);
		if (nameLength < Name.MIN_NAME_SIZE || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check data size bounds
		int dataLength = Utf8.encodedLength(this.registerNameTransactionData.getData());
		if (dataLength > Name.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// Check name is in normalized form (no leading/trailing whitespace, etc.)
		if (!name.equals(Unicode.normalize(name)))
			return ValidationResult.NAME_NOT_NORMALIZED;

		// Check name doesn't look like an address
		if (Crypto.isValidAddress(name))
			return ValidationResult.INVALID_ADDRESS;

		// Check registrant has enough funds
		if (registrant.getConfirmedBalance(Asset.QORT) < this.registerNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Check the name isn't already taken
		if (this.repository.getNameRepository().reducedNameExists(this.registerNameTransactionData.getReducedName())) {
			// Name exists, but we'll allow the transaction if it has the same creator
			// This is necessary to workaround an issue due to inconsistent data in the Names table on some nodes.
			// Without this, the chain can get stuck for a subset of nodes when the name is registered
			// for the second time. It's simplest to just treat REGISTER_NAME as UPDATE_NAME if the creator
			// matches that of the original registration.

			NameData nameData = this.repository.getNameRepository().fromReducedName(this.registerNameTransactionData.getReducedName());
			if (Objects.equals(this.getCreator().getAddress(), nameData.getOwner())) {
				// Transaction creator already owns the name, so it's safe to update it
				// Treat this as valid, which also requires skipping the "one name per account" check below.
				// Given that the name matches one already registered, we know that it won't exceed the limit.
				return ValidationResult.OK;
			}

			// Name is already registered to someone else
			return ValidationResult.NAME_ALREADY_REGISTERED;
		}

		// If accounts are only allowed one registered name then check for this
		if (BlockChain.getInstance().oneNamePerAccount()
				&& !this.repository.getNameRepository().getNamesByOwner(getRegistrant().getAddress()).isEmpty())
			return ValidationResult.MULTIPLE_NAMES_FORBIDDEN;

		// FUTURE: when adding more validation, make sure to check the `return ValidationResult.OK` above

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Register Name
		Name name = new Name(this.repository, this.registerNameTransactionData);
		name.register();
	}

	@Override
	public void orphan() throws DataException {
		// Unregister name
		Name name = new Name(this.repository, this.registerNameTransactionData.getName());
		name.unregister();
	}

}
