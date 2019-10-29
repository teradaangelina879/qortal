package org.qora.repository;

import java.util.List;

import org.qora.data.account.AccountBalanceData;
import org.qora.data.account.AccountData;
import org.qora.data.account.MintingAccountData;
import org.qora.data.account.RewardShareData;

public interface AccountRepository {

	// General account

	/** Returns all general information about account, e.g. public key, last reference, default group ID. */
	public AccountData getAccount(String address) throws DataException;

	/** Returns account's last reference or null if not set or account not found. */
	public byte[] getLastReference(String address) throws DataException;

	/** Returns account's default groupID or null if account not found. */
	public Integer getDefaultGroupId(String address) throws DataException;

	/** Returns account's flags or null if account not found. */
	public Integer getFlags(String address) throws DataException;

	/** Returns account's level or null if account not found. */
	public Integer getLevel(String address) throws DataException;

	/** Returns whether account exists. */
	public boolean accountExists(String address) throws DataException;

	/**
	 * Ensures at least minimal account info in repository.
	 * <p>
	 * Saves account address, and public key if present.
	 */
	public void ensureAccount(AccountData accountData) throws DataException;

	/**
	 * Saves account's last reference, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like default group ID.
	 */
	public void setLastReference(AccountData accountData) throws DataException;

	/**
	 * Saves account's default groupID, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference.
	 */
	public void setDefaultGroupId(AccountData accountData) throws DataException;

	/**
	 * Saves account's flags, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setFlags(AccountData accountData) throws DataException;

	/**
	 * Saves account's level, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setLevel(AccountData accountData) throws DataException;

	/**
	 * Saves account's initial & current level, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setInitialLevel(AccountData accountData) throws DataException;

	/**
	 * Saves account's minted block count and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setMintedBlockCount(AccountData accountData) throws DataException;

	/** Delete account from repository. */
	public void delete(String address) throws DataException;

	// Account balances

	public AccountBalanceData getBalance(String address, long assetId) throws DataException;

	public enum BalanceOrdering {
		ASSET_BALANCE_ACCOUNT,
		ACCOUNT_ASSET,
		ASSET_ACCOUNT
	}

	public List<AccountBalanceData> getAssetBalances(List<String> addresses, List<Long> assetIds, BalanceOrdering balanceOrdering, Boolean excludeZero, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public void save(AccountBalanceData accountBalanceData) throws DataException;

	public void delete(String address, long assetId) throws DataException;

	// Reward-shares

	public RewardShareData getRewardShare(byte[] mintingAccountPublicKey, String recipientAccount) throws DataException;

	public RewardShareData getRewardShare(byte[] rewardSharePublicKey) throws DataException;

	public boolean isRewardSharePublicKey(byte[] publicKey) throws DataException;

	/** Returns number of active reward-shares involving passed public key as the minting account only. */
	public int countRewardShares(byte[] mintingAccountPublicKey) throws DataException;

	public List<RewardShareData> getRewardShares() throws DataException;

	public List<RewardShareData> findRewardShares(List<String> mintingAccounts, List<String> recipientAccounts, List<String> involvedAddresses, Integer limit, Integer offset, Boolean reverse) throws DataException;

	/**
	 * Returns index in list of reward-shares (sorted by reward-share public key).
	 * <p>
	 * @return index (from 0) or null if publicKey not found in repository.
	 */
	public Integer getRewardShareIndex(byte[] rewardSharePublicKey) throws DataException;

	/**
	 * Returns reward-share data using index into list of reward-shares (sorted by reward-share public key).
	 */
	public RewardShareData getRewardShareByIndex(int index) throws DataException;

	public void save(RewardShareData rewardShareData) throws DataException;

	/** Delete reward-share from repository using passed minting account's public key and recipient's address. */
	public void delete(byte[] mintingAccountPublickey, String recipient) throws DataException;

	// Minting accounts used by BlockMinter, potentially includes reward-shares

	public List<MintingAccountData> getMintingAccounts() throws DataException;

	public void save(MintingAccountData mintingAccountData) throws DataException;

	/** Delete minting account info, used by BlockMinter, from repository using passed private key. */
	public int delete(byte[] mintingAccountPrivateKey) throws DataException;

}
