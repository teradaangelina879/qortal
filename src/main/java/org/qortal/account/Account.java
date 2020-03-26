package org.qortal.account;

import java.math.BigDecimal;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;

public class Account {

	private static final Logger LOGGER = LogManager.getLogger(Account.class);

	public static final int ADDRESS_LENGTH = 25;
	public static final int FOUNDER_FLAG = 0x1;

	protected Repository repository;
	protected String address;

	protected Account() {
	}

	/** Construct Account business object using account's address */
	public Account(Repository repository, String address) {
		this.repository = repository;
		this.address = address;
	}

	// Simple getters / setters

	public String getAddress() {
		return this.address;
	}

	/**
	 * Build AccountData object using available account information.
	 * <p>
	 * For example, PublicKeyAccount might override and add public key info.
	 * 
	 * @return
	 */
	protected AccountData buildAccountData() {
		return new AccountData(this.address);
	}

	// Balance manipulations - assetId is 0 for QORT

	public BigDecimal getBalance(long assetId) throws DataException {
		AccountBalanceData accountBalanceData = this.repository.getAccountRepository().getBalance(this.address, assetId);
		if (accountBalanceData == null)
			return BigDecimal.ZERO.setScale(8);

		return accountBalanceData.getBalance();
	}

	public BigDecimal getConfirmedBalance(long assetId) throws DataException {
		AccountBalanceData accountBalanceData = this.repository.getAccountRepository().getBalance(this.address, assetId);
		if (accountBalanceData == null)
			return BigDecimal.ZERO.setScale(8);

		return accountBalanceData.getBalance();
	}

	public void setConfirmedBalance(long assetId, BigDecimal balance) throws DataException {
		// Safety feature!
		if (balance.compareTo(BigDecimal.ZERO) < 0) {
			String message = String.format("Refusing to set negative balance %s [assetId %d] for %s", balance.toPlainString(), assetId, this.address);
			LOGGER.error(message);
			throw new DataException(message);
		}

		// Can't have a balance without an account - make sure it exists!
		this.repository.getAccountRepository().ensureAccount(this.buildAccountData());

		AccountBalanceData accountBalanceData = new AccountBalanceData(this.address, assetId, balance);
		this.repository.getAccountRepository().save(accountBalanceData);

		LOGGER.trace(() -> String.format("%s balance now %s [assetId %s]", this.address, balance.toPlainString(), assetId));
	}

	public void deleteBalance(long assetId) throws DataException {
		this.repository.getAccountRepository().delete(this.address, assetId);
	}

	// Reference manipulations

	/**
	 * Fetch last reference for account.
	 * 
	 * @return byte[] reference, or null if no reference or account not found.
	 * @throws DataException
	 */
	public byte[] getLastReference() throws DataException {
		byte[] reference = this.repository.getAccountRepository().getLastReference(this.address);
		LOGGER.trace(() -> String.format("Last reference for %s is %s", this.address, reference == null ? "null" : Base58.encode(reference)));
		return reference;
	}

	/**
	 * Fetch last reference for account, considering unconfirmed transactions only, or return null.
	 * <p>
	 * NOTE: calls Transaction.getUnconfirmedTransactions which discards uncommitted
	 * repository changes.
	 * 
	 * @return byte[] reference, or null if no unconfirmed transactions for this account.
	 * @throws DataException
	 */
	public byte[] getUnconfirmedLastReference() throws DataException {
		// Newest unconfirmed transaction takes priority
		List<TransactionData> unconfirmedTransactions = Transaction.getUnconfirmedTransactions(repository);

		byte[] reference = null;

		for (TransactionData transactionData : unconfirmedTransactions) {
			String unconfirmedTransactionAddress = PublicKeyAccount.getAddress(transactionData.getCreatorPublicKey());

			if (unconfirmedTransactionAddress.equals(this.address))
				reference = transactionData.getSignature();
		}

		final byte[] loggingReference = reference;
		LOGGER.trace(() -> String.format("Last unconfirmed reference for %s is %s", this.address, loggingReference == null ? "null" : Base58.encode(loggingReference)));
		return reference;
	}

	/**
	 * Set last reference for account.
	 * 
	 * @param reference
	 *            -- null allowed
	 * @throws DataException
	 */
	public void setLastReference(byte[] reference) throws DataException {
		LOGGER.trace(() -> String.format("Setting last reference for %s to %s", this.address, (reference == null ? "null" : Base58.encode(reference))));

		AccountData accountData = this.buildAccountData();
		accountData.setReference(reference);
		this.repository.getAccountRepository().setLastReference(accountData);
	}

	// Default groupID manipulations

	/** Returns account's default groupID or null if account doesn't exist. */
	public Integer getDefaultGroupId() throws DataException {
		return this.repository.getAccountRepository().getDefaultGroupId(this.address);
	}

	/**
	 * Sets account's default groupID and saves into repository.
	 * <p>
	 * Caller will need to call <tt>repository.saveChanges()</tt>.
	 * 
	 * @param defaultGroupId
	 * @throws DataException
	 */
	public void setDefaultGroupId(int defaultGroupId) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setDefaultGroupId(defaultGroupId);
		this.repository.getAccountRepository().setDefaultGroupId(accountData);

		LOGGER.trace(() -> String.format("Account %s defaultGroupId now %d", accountData.getAddress(), defaultGroupId));
	}

	// Account flags

	public Integer getFlags() throws DataException {
		return this.repository.getAccountRepository().getFlags(this.address);
	}

	public void setFlags(int flags) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setFlags(flags);
		this.repository.getAccountRepository().setFlags(accountData);
	}

	public static boolean isFounder(Integer flags) {
		return flags != null && (flags & FOUNDER_FLAG) != 0;
	}

	public boolean isFounder() throws DataException  {
		Integer flags = this.getFlags();
		return Account.isFounder(flags);
	}

	// Minting blocks

	/** Returns whether account can be considered a "minting account".
	 * <p>
	 * To be considered a "minting account", the account needs to pass at least one of these tests:<br>
	 * <ul>
	 * <li>account's level is at least <tt>minAccountLevelToMint</tt> from blockchain config</li>
	 * <li>account has 'founder' flag set</li>
	 * </ul>
	 * 
	 * @return true if account can be considered "minting account"
	 * @throws DataException
	 */
	public boolean canMint() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		if (accountData == null)
			return false;

		Integer level = accountData.getLevel();
		if (level != null && level >= BlockChain.getInstance().getMinAccountLevelToMint())
			return true;

		if (Account.isFounder(accountData.getFlags()))
			return true;

		return false;
	}

	/** Returns whether account can build reward-shares.
	 * <p>
	 * To be able to create reward-shares, the account needs to pass at least one of these tests:<br>
	 * <ul>
	 * <li>account's level is at least <tt>minAccountLevelToRewardShare</tt> from blockchain config</li>
	 * <li>account has 'founder' flag set</li>
	 * </ul>
	 * 
	 * @return true if account can be considered "minting account"
	 * @throws DataException
	 */
	public boolean canRewardShare() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		if (accountData == null)
			return false;

		Integer level = accountData.getLevel();
		if (level != null && level >= BlockChain.getInstance().getMinAccountLevelToRewardShare())
			return true;

		if (Account.isFounder(accountData.getFlags()))
			return true;

		return false;
	}

	// Account level

	/** Returns account's level (0+) or null if account not found in repository. */
	public Integer getLevel() throws DataException {
		return this.repository.getAccountRepository().getLevel(this.address);
	}

	public void setLevel(int level) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setLevel(level);
		this.repository.getAccountRepository().setLevel(accountData);
	}

	public void setBlocksMintedAdjustment(int blocksMintedAdjustment) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setBlocksMintedAdjustment(blocksMintedAdjustment);
		this.repository.getAccountRepository().setBlocksMintedAdjustment(accountData);
	}

	/**
	 * Returns 'effective' minting level, or zero if account does not exist/cannot mint.
	 * <p>
	 * For founder accounts, this returns "founderEffectiveMintingLevel" from blockchain config.
	 * 
	 * @return 0+
	 * @throws DataException
	 */
	public int getEffectiveMintingLevel() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		if (accountData == null)
			return 0;

		if (Account.isFounder(accountData.getFlags()))
			return BlockChain.getInstance().getFounderEffectiveMintingLevel();

		Integer level = accountData.getLevel();
		if (level == null)
			return 0;

		return level;
	}

	/**
	 * Returns 'effective' minting level, or zero if reward-share does not exist.
	 * <p>
	 * For founder accounts, this returns "founderEffectiveMintingLevel" from blockchain config.
	 * 
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return 0+
	 * @throws DataException
	 */
	public static int getRewardShareEffectiveMintingLevel(Repository repository, byte[] rewardSharePublicKey) throws DataException {
		// Find actual minter and get their effective minting level
		RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardSharePublicKey);
		if (rewardShareData == null)
			return 0;

		Account rewardShareMinter = new Account(repository, rewardShareData.getMinter());
		return rewardShareMinter.getEffectiveMintingLevel();
	}

}
