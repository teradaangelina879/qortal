package org.qora.transaction;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.qora.account.Account;
import org.qora.account.GenesisAccount;
import org.qora.asset.Asset;
import org.qora.data.transaction.AccountLevelTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;

public class AccountLevelTransaction extends Transaction {

	// Properties
	private AccountLevelTransactionData accountLevelTransactionData;

	// Constructors

	public AccountLevelTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.accountLevelTransactionData = (AccountLevelTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() throws DataException {
		return Collections.emptyList();
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getCreator().getAddress()))
			return true;

		if (address.equals(this.getTarget().getAddress()))
			return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);

		if (address.equals(this.getCreator().getAddress()))
			amount = amount.subtract(this.transactionData.getFee());

		return amount;
	}

	// Navigation

	public Account getTarget() {
		return new Account(this.repository, this.accountLevelTransactionData.getTarget());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		Account creator = getCreator();

		// Only genesis account can modify level
		if (!creator.getAddress().equals(new GenesisAccount(repository).getAddress()))
			return ValidationResult.NO_FLAG_PERMISSION;

		// Check fee is zero or positive
		if (accountLevelTransactionData.getFee().compareTo(BigDecimal.ZERO) < 0)
			return ValidationResult.NEGATIVE_FEE;

		// Check creator has enough funds
		if (creator.getConfirmedBalance(Asset.QORT).compareTo(accountLevelTransactionData.getFee()) < 0)
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		Account target = getTarget();

		// Save this transaction
		this.repository.getTransactionRepository().save(accountLevelTransactionData);

		// Set account's initial level
		target.setInitialLevel(this.accountLevelTransactionData.getLevel());
	}

	@Override
	public void orphan() throws DataException {
		// Revert
		Account target = getTarget();

		// This is only ever a genesis block transaction so simply delete account
		this.repository.getAccountRepository().delete(target.getAddress());
	}

}
