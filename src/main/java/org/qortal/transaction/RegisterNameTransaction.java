package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.naming.Name;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

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
		return Collections.singletonList(this.registerNameTransactionData.getOwner());
	}

	// Navigation

	public Account getRegistrant() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		Account registrant = getRegistrant();

		// Check owner address is valid
		if (!Crypto.isValidAddress(this.registerNameTransactionData.getOwner()))
			return ValidationResult.INVALID_ADDRESS;

		// Check name size bounds
		int nameLength = Utf8.encodedLength(this.registerNameTransactionData.getName());
		if (nameLength < 1 || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check data size bounds
		int dataLength = Utf8.encodedLength(this.registerNameTransactionData.getData());
		if (dataLength < 1 || dataLength > Name.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// Check name is lowercase
		if (!this.registerNameTransactionData.getName().equals(this.registerNameTransactionData.getName().toLowerCase()))
			return ValidationResult.NAME_NOT_LOWER_CASE;

		// Check registrant has enough funds
		if (registrant.getConfirmedBalance(Asset.QORT) < this.registerNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Check the name isn't already taken
		if (this.repository.getNameRepository().nameExists(this.registerNameTransactionData.getName()))
			return ValidationResult.NAME_ALREADY_REGISTERED;

		Account registrant = getRegistrant();

		// If accounts are only allowed one registered name then check for this
		if (BlockChain.getInstance().oneNamePerAccount() && !this.repository.getNameRepository().getNamesByOwner(registrant.getAddress()).isEmpty())
			return ValidationResult.MULTIPLE_NAMES_FORBIDDEN;

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
