package org.qora.transaction;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qora.account.Account;
import org.qora.account.PublicKeyAccount;
import org.qora.block.BlockChain;
import org.qora.crypto.Crypto;
import org.qora.data.account.AccountData;
import org.qora.data.transaction.TransactionData;
import org.qora.data.transaction.TransferPrivsTransactionData;
import org.qora.repository.AccountRepository;
import org.qora.repository.DataException;
import org.qora.repository.Repository;

public class TransferPrivsTransaction extends Transaction {

	private static final Logger LOGGER = LogManager.getLogger(TransferPrivsTransaction.class);

	// Properties
	private TransferPrivsTransactionData transferPrivsTransactionData;

	// Constructors

	public TransferPrivsTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.transferPrivsTransactionData = (TransferPrivsTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() throws DataException {
		return Collections.singletonList(new Account(this.repository, transferPrivsTransactionData.getRecipient()));
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getSender().getAddress()))
			return true;

		if (address.equals(transferPrivsTransactionData.getRecipient()))
			return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);
		String senderAddress = this.getSender().getAddress();

		if (address.equals(senderAddress))
			amount = amount.subtract(this.transactionData.getFee());

		return amount;
	}

	// Navigation

	public Account getSender() throws DataException {
		return new PublicKeyAccount(this.repository, this.transferPrivsTransactionData.getSenderPublicKey());
	}

	public Account getRecipient() throws DataException {
		return new Account(this.repository, this.transferPrivsTransactionData.getRecipient());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check recipient address is valid
		if (!Crypto.isValidAddress(this.transferPrivsTransactionData.getRecipient()))
			return ValidationResult.INVALID_ADDRESS;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		Account sender = this.getSender();
		Account recipient = this.getRecipient();

		int senderFlags = sender.getFlags(); // Sender must exist so we always expect a result
		Integer recipientFlags = recipient.getFlags(); // Recipient might not exist yet, so null possible

		// Save prior values
		this.transferPrivsTransactionData.setPreviousSenderFlags(senderFlags);
		this.transferPrivsTransactionData.setPreviousRecipientFlags(recipientFlags);

		// Combine sender & recipient flags for recipient
		if (recipientFlags != null)
			senderFlags |= recipientFlags;

		recipient.setFlags(senderFlags);

		// Clear sender's flags
		sender.setFlags(0);

		// Combine blocks minted counts/adjustments
		final AccountRepository accountRepository = this.repository.getAccountRepository();

		AccountData senderData = accountRepository.getAccount(sender.getAddress());
		int sendersBlocksMinted = senderData.getBlocksMinted();
		int sendersBlocksMintedAdjustment = senderData.getBlocksMintedAdjustment();

		AccountData recipientData = accountRepository.getAccount(recipient.getAddress());
		int recipientBlocksMinted = recipientData != null ? recipientData.getBlocksMinted() : 0;
		int recipientBlocksMintedAdjustment = recipientData != null ? recipientData.getBlocksMintedAdjustment() : 0;

		// Save prior values
		this.transferPrivsTransactionData.setPreviousSenderBlocksMinted(sendersBlocksMinted);
		this.transferPrivsTransactionData.setPreviousSenderBlocksMintedAdjustment(sendersBlocksMintedAdjustment);

		// Combine blocks minted
		recipientData.setBlocksMinted(recipientBlocksMinted + sendersBlocksMinted);
		accountRepository.setMintedBlockCount(recipientData);
		recipientData.setBlocksMintedAdjustment(recipientBlocksMintedAdjustment + sendersBlocksMintedAdjustment);
		accountRepository.setBlocksMintedAdjustment(recipientData);

		// Determine new recipient level based on blocks
		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;
		final int effectiveBlocksMinted = recipientData.getBlocksMinted() + recipientData.getBlocksMintedAdjustment();

		for (int newLevel = maximumLevel; newLevel > 0; --newLevel)
			if (effectiveBlocksMinted >= cumulativeBlocksByLevel.get(newLevel)) {
				if (newLevel > recipientData.getLevel()) {
					// Account has increased in level!
					recipientData.setLevel(newLevel);
					accountRepository.setLevel(recipientData);
					LOGGER.trace(() -> String.format("TRANSFER_PRIVS recipient %s bumped to level %d", recipientData.getAddress(), recipientData.getLevel()));
				}

				break;
			}

		// Reset sender's level
		sender.setLevel(0);

		// Reset sender's blocks minted count & adjustment
		senderData.setBlocksMinted(0);
		accountRepository.setMintedBlockCount(senderData);
		senderData.setBlocksMintedAdjustment(0);
		accountRepository.setBlocksMintedAdjustment(senderData);

		// Save this transaction
		this.repository.getTransactionRepository().save(this.transferPrivsTransactionData);
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		super.processReferencesAndFees();

		// If recipient has no last-reference then use this transaction's signature as last-reference so they can spend their block rewards
		Account recipient = new Account(this.repository, transferPrivsTransactionData.getRecipient());
		if (recipient.getLastReference() == null)
			recipient.setLastReference(transferPrivsTransactionData.getSignature());
	}

	@Override
	public void orphan() throws DataException {
		Account sender = this.getSender();
		Account recipient = this.getRecipient();

		final AccountRepository accountRepository = this.repository.getAccountRepository();

		AccountData senderData = accountRepository.getAccount(sender.getAddress());
		AccountData recipientData = accountRepository.getAccount(recipient.getAddress());

		// Restore sender's flags
		senderData.setFlags(this.transferPrivsTransactionData.getPreviousSenderFlags());
		accountRepository.setFlags(senderData);

		// Restore recipient's flags
		recipientData.setFlags(this.transferPrivsTransactionData.getPreviousRecipientFlags());
		accountRepository.setFlags(recipientData);

		// Clean values in transaction data
		this.transferPrivsTransactionData.setPreviousSenderFlags(null);
		this.transferPrivsTransactionData.setPreviousRecipientFlags(null);

		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;

		// Restore sender's block minted count/adjustment
		senderData.setBlocksMinted(this.transferPrivsTransactionData.getPreviousSenderBlocksMinted());
		accountRepository.setMintedBlockCount(senderData);
		senderData.setBlocksMintedAdjustment(this.transferPrivsTransactionData.getPreviousSenderBlocksMintedAdjustment());
		accountRepository.setBlocksMintedAdjustment(senderData);

		// Recalculate sender's level
		int effectiveBlocksMinted = senderData.getBlocksMinted() + senderData.getBlocksMintedAdjustment();
		for (int newLevel = maximumLevel; newLevel > 0; --newLevel)
			if (effectiveBlocksMinted >= cumulativeBlocksByLevel.get(newLevel)) {
				// Account level
				senderData.setLevel(newLevel);
				accountRepository.setLevel(senderData);
				LOGGER.trace(() -> String.format("TRANSFER_PRIVS sender %s reset to level %d", senderData.getAddress(), senderData.getLevel()));

				break;
			}

		// Restore recipient block minted count/adjustment
		recipientData.setBlocksMinted(recipientData.getBlocksMinted() - this.transferPrivsTransactionData.getPreviousSenderBlocksMinted());
		accountRepository.setMintedBlockCount(recipientData);
		recipientData.setBlocksMintedAdjustment(recipientData.getBlocksMintedAdjustment() - this.transferPrivsTransactionData.getPreviousSenderBlocksMintedAdjustment());
		accountRepository.setBlocksMintedAdjustment(recipientData);

		// Recalculate recipient's level
		effectiveBlocksMinted = recipientData.getBlocksMinted() + recipientData.getBlocksMintedAdjustment();
		for (int newLevel = maximumLevel; newLevel > 0; --newLevel)
			if (effectiveBlocksMinted >= cumulativeBlocksByLevel.get(newLevel)) {
				// Account level
				recipientData.setLevel(newLevel);
				accountRepository.setLevel(recipientData);
				LOGGER.trace(() -> String.format("TRANSFER_PRIVS recipient %s reset to level %d", recipientData.getAddress(), recipientData.getLevel()));

				break;
			}

		// Clear values in transaction data
		this.transferPrivsTransactionData.setPreviousSenderBlocksMinted(null);
		this.transferPrivsTransactionData.setPreviousSenderBlocksMintedAdjustment(null);

		// Save this transaction
		this.repository.getTransactionRepository().save(this.transferPrivsTransactionData);
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		super.orphanReferencesAndFees();

		// If recipient didn't have a last-reference prior to this transaction then remove it
		Account recipient = new Account(this.repository, transferPrivsTransactionData.getRecipient());
		if (Arrays.equals(recipient.getLastReference(), transferPrivsTransactionData.getSignature()))
			recipient.setLastReference(null);
	}

}
