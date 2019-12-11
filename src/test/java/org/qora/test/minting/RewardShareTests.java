package org.qora.test.minting;

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
import org.qora.test.common.TransactionUtils;
import org.qora.transaction.Transaction;
import org.qora.transaction.Transaction.ValidationResult;
import org.qora.utils.Base58;

public class RewardShareTests extends Common {

	private static final BigDecimal CANCEL_SHARE_PERCENT = BigDecimal.ONE.negate();

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
			byte[] newRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", CANCEL_SHARE_PERCENT);
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
	public void testNegativeInitialShareInvalid() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create invalid REWARD_SHARE transaction with initial negative reward share
			TransactionData transactionData = AccountUtils.createRewardShare(repository, "alice", "bob", CANCEL_SHARE_PERCENT);

			// Confirm transaction is invalid
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult validationResult = transaction.isValidUnconfirmed();
			assertEquals("Initial 0% share should be invalid", ValidationResult.INVALID_REWARD_SHARE_PERCENT, validationResult);
		}
	}

	@Test
	public void testSelfShare() throws DataException {
		final String testAccountName = "dilbert";

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount signingAccount = Common.getTestAccount(repository, testAccountName);
			// byte[] rewardSharePrivateKey = aliceAccount.getRewardSharePrivateKey(aliceAccount.getPublicKey());
			// PrivateKeyAccount rewardShareAccount = new PrivateKeyAccount(repository, rewardSharePrivateKey);

			// Create self-reward-share
			TransactionData transactionData = AccountUtils.createRewardShare(repository, testAccountName, testAccountName, BigDecimal.valueOf(100L));
			Transaction transaction = Transaction.fromData(repository, transactionData);

			// Confirm self-share is valid
			ValidationResult validationResult = transaction.isValidUnconfirmed();
			assertEquals("Initial self-share should be valid", ValidationResult.OK, validationResult);

			// Check zero fee is valid
			transactionData.setFee(BigDecimal.ZERO);
			validationResult = transaction.isValidUnconfirmed();
			assertEquals("Zero-fee self-share should be valid", ValidationResult.OK, validationResult);

			TransactionUtils.signAndMint(repository, transactionData, signingAccount);

			// Subsequent non-terminating (0% share) self-reward-share should be invalid
			TransactionData newTransactionData = AccountUtils.createRewardShare(repository, testAccountName, testAccountName, BigDecimal.valueOf(99L));
			Transaction newTransaction = Transaction.fromData(repository, newTransactionData);

			// Confirm subsequent self-reward-share is actually invalid
			validationResult = newTransaction.isValidUnconfirmed();
			assertNotSame("Subsequent self-share should be invalid", ValidationResult.OK, validationResult);

			// Recheck with zero fee
			newTransactionData.setFee(BigDecimal.ZERO);
			validationResult = newTransaction.isValidUnconfirmed();
			assertNotSame("Subsequent zero-fee self-share should be invalid", ValidationResult.OK, validationResult);

			// Subsequent terminating (negative share) self-reward-share should be OK
			newTransactionData = AccountUtils.createRewardShare(repository, testAccountName, testAccountName, CANCEL_SHARE_PERCENT);
			newTransaction = Transaction.fromData(repository, newTransactionData);

			// Confirm terminating reward-share is valid
			validationResult = newTransaction.isValidUnconfirmed();
			assertEquals("Subsequent zero-fee self-share should be invalid", ValidationResult.OK, validationResult);
		}
	}

}
