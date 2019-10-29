package org.qora.test.forging;

import static org.junit.Assert.*;

import java.math.BigDecimal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qora.account.PrivateKeyAccount;
import org.qora.data.account.RewardShareData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.test.common.AccountUtils;
import org.qora.test.common.BlockUtils;
import org.qora.test.common.Common;
import org.qora.transaction.Transaction;
import org.qora.transaction.Transaction.ValidationResult;
import org.qora.utils.Base58;

public class RewardShareTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testCreateRewardShare() throws DataException {
		final BigDecimal sharePercent = new BigDecimal("12.8");

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Create reward-share
			byte[] rewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", sharePercent);
			PrivateKeyAccount rewardShareAccount = new PrivateKeyAccount(repository, rewardSharePrivateKey);

			// Confirm reward-share info set correctly

			// Fetch using reward-share public key
			RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardShareAccount.getPublicKey());
			assertEquals("Incorrect minter public key", Base58.encode(aliceAccount.getPublicKey()), Base58.encode(rewardShareData.getMinterPublicKey()));
			assertEquals("Incorrect recipient", bobAccount.getAddress(), rewardShareData.getRecipient());
			assertEqualBigDecimals("Incorrect share percentage", sharePercent, rewardShareData.getSharePercent());

			// Fetch using minter public key and recipient address combination
			rewardShareData = repository.getAccountRepository().getRewardShare(aliceAccount.getPublicKey(), bobAccount.getAddress());
			assertEquals("Incorrect minter public key", Base58.encode(aliceAccount.getPublicKey()), Base58.encode(rewardShareData.getMinterPublicKey()));
			assertEquals("Incorrect recipient", bobAccount.getAddress(), rewardShareData.getRecipient());
			assertEqualBigDecimals("Incorrect share percentage", sharePercent, rewardShareData.getSharePercent());

			// Delete reward-share
			byte[] newRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", BigDecimal.ZERO);
			PrivateKeyAccount newRewardShareAccount = new PrivateKeyAccount(repository, newRewardSharePrivateKey);

			// Confirm reward-share keys match
			assertEquals("Reward-share private keys differ", Base58.encode(rewardSharePrivateKey), Base58.encode(newRewardSharePrivateKey));
			assertEquals("Reward-share public keys differ", Base58.encode(rewardShareAccount.getPublicKey()), Base58.encode(newRewardShareAccount.getPublicKey()));

			// Confirm reward-share no longer exists in repository

			// Fetch using reward-share public key
			RewardShareData newRewardShareData = repository.getAccountRepository().getRewardShare(rewardShareAccount.getPublicKey());
			assertNull("Reward-share shouldn't exist", newRewardShareData);

			// Fetch using minter public key and recipient address combination
			newRewardShareData = repository.getAccountRepository().getRewardShare(aliceAccount.getPublicKey(), bobAccount.getAddress());
			assertNull("Reward-share shouldn't exist", newRewardShareData);

			// Orphan last block to restore prior reward-share
			BlockUtils.orphanLastBlock(repository);

			// Confirm reward-share restored correctly

			// Fetch using reward-share public key
			newRewardShareData = repository.getAccountRepository().getRewardShare(rewardShareAccount.getPublicKey());
			assertNotNull("Reward-share should have been restored", newRewardShareData);
			assertEquals("Incorrect minter public key", Base58.encode(aliceAccount.getPublicKey()), Base58.encode(newRewardShareData.getMinterPublicKey()));
			assertEquals("Incorrect recipient", bobAccount.getAddress(), newRewardShareData.getRecipient());
			assertEqualBigDecimals("Incorrect share percentage", sharePercent, newRewardShareData.getSharePercent());

			// Fetch using minter public key and recipient address combination
			newRewardShareData = repository.getAccountRepository().getRewardShare(aliceAccount.getPublicKey(), bobAccount.getAddress());
			assertNotNull("Reward-share should have been restored", newRewardShareData);
			assertEquals("Incorrect minter public key", Base58.encode(aliceAccount.getPublicKey()), Base58.encode(newRewardShareData.getMinterPublicKey()));
			assertEquals("Incorrect recipient", bobAccount.getAddress(), newRewardShareData.getRecipient());
			assertEqualBigDecimals("Incorrect share percentage", sharePercent, newRewardShareData.getSharePercent());

			// Orphan another block to remove initial reward-share
			BlockUtils.orphanLastBlock(repository);

			// Confirm reward-share no longer exists

			// Fetch using reward-share public key
			newRewardShareData = repository.getAccountRepository().getRewardShare(rewardShareAccount.getPublicKey());
			assertNull("Reward-share shouldn't exist", newRewardShareData);

			// Fetch using minter public key and recipient address combination
			newRewardShareData = repository.getAccountRepository().getRewardShare(aliceAccount.getPublicKey(), bobAccount.getAddress());
			assertNull("Reward-share shouldn't exist", newRewardShareData);
		}
	}

	@Test
	public void testZeroInitialShareInvalid() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create invalid REWARD_SHARE transaction with initial 0% reward share
			TransactionData transactionData = AccountUtils.createRewardShare(repository, "alice", "bob", BigDecimal.ZERO);

			// Confirm transaction is invalid
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult validationResult = transaction.isValidUnconfirmed();
			assertEquals("Initial 0% share should be invalid", ValidationResult.INVALID_REWARD_SHARE_PERCENT, validationResult);
		}
	}

}
