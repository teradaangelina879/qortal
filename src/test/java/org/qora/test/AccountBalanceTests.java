package org.qora.test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qora.account.Account;
import org.qora.account.PrivateKeyAccount;
import org.qora.account.PublicKeyAccount;
import org.qora.asset.Asset;
import org.qora.block.BlockChain;
import org.qora.data.account.AccountBalanceData;
import org.qora.data.transaction.BaseTransactionData;
import org.qora.data.transaction.PaymentTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.test.common.BlockUtils;
import org.qora.test.common.Common;
import org.qora.test.common.TestAccount;
import org.qora.test.common.TransactionUtils;

public class AccountBalanceTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	/** Tests that newer balances are returned instead of older ones. */
	@Test
	public void testNewerBalance() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			testNewerBalance(repository, alice);
		}
	}

	private BigDecimal testNewerBalance(Repository repository, TestAccount testAccount) throws DataException {
		// Grab initial balance
		BigDecimal initialBalance = testAccount.getConfirmedBalance(Asset.QORT);

		// Mint block to cause newer balance
		BlockUtils.mintBlock(repository);

		// Grab newer balance
		BigDecimal newerBalance = testAccount.getConfirmedBalance(Asset.QORT);

		// Confirm newer balance is greater than initial balance
		assertTrue("Newer balance should be greater than initial balance", newerBalance.compareTo(initialBalance) > 0);

		return initialBalance;
	}

	/** Tests that orphaning reverts balance back to initial. */
	@Test
	public void testOrphanedBalance() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			BigDecimal initialBalance = testNewerBalance(repository, alice);

			BlockUtils.orphanLastBlock(repository);

			// Grab post-orphan balance
			BigDecimal orphanedBalance = alice.getConfirmedBalance(Asset.QORT);

			// Confirm post-orphan balance is same as initial
			assertTrue("Post-orphan balance should match initial", orphanedBalance.equals(initialBalance));
		}
	}

	/** Tests we can fetch initial balance when newer balance exists. */
	@Test
	public void testGetBalanceAtHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			BigDecimal initialBalance = testNewerBalance(repository, alice);

			// Fetch balance at height 1, even though newer balance exists
			AccountBalanceData accountBalanceData = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT, 1);
			BigDecimal genesisBalance = accountBalanceData.getBalance();

			// Confirm genesis balance is same as initial
			assertTrue("Genesis balance should match initial", genesisBalance.equals(initialBalance));
		}
	}

	/** Tests we can fetch balance with a height where no balance change occurred. */
	@Test
	public void testGetBalanceAtNearestHeight() throws DataException {
		Random random = new Random();

		byte[] publicKey = new byte[32];
		random.nextBytes(publicKey);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PublicKeyAccount recipientAccount = new PublicKeyAccount(repository, publicKey);

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			// Confirm recipient balance is zero
			BigDecimal balance = recipientAccount.getConfirmedBalance(Asset.QORT);
			assertTrue("recipient's balance should be zero", balance.signum() == 0);

			// Send 1 QORT to recipient
			TestAccount sendingAccount = Common.getTestAccount(repository, "alice");
			pay(repository, sendingAccount, recipientAccount, BigDecimal.ONE);

			// Mint some more blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			// Send more QORT to recipient
			BigDecimal amount = BigDecimal.valueOf(random.nextInt(123456));
			pay(repository, sendingAccount, recipientAccount, amount);

			// Mint some more blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			// Confirm recipient balance is as expected
			BigDecimal totalAmount = amount.add(BigDecimal.ONE);
			balance = recipientAccount.getConfirmedBalance(Asset.QORT);
			assertTrue("recipient's balance incorrect", balance.compareTo(totalAmount) == 0);

			// Confirm balance as of 2 blocks ago
			int height = repository.getBlockRepository().getBlockchainHeight();
			balance = repository.getAccountRepository().getBalance(recipientAccount.getAddress(), Asset.QORT, height - 2).getBalance();
			assertTrue("recipient's historic balance incorrect", balance.compareTo(totalAmount) == 0);

			// Confirm balance prior to last payment
			balance = repository.getAccountRepository().getBalance(recipientAccount.getAddress(), Asset.QORT, height - 15).getBalance();
			assertTrue("recipient's historic balance incorrect", balance.compareTo(BigDecimal.ONE) == 0);

			// Orphan blocks to before last payment
			BlockUtils.orphanBlocks(repository, 10 + 5);

			// Re-check balance from (now) invalid height
			AccountBalanceData accountBalanceData = repository.getAccountRepository().getBalance(recipientAccount.getAddress(), Asset.QORT, height - 2);
			balance = accountBalanceData.getBalance();
			assertTrue("recipient's invalid-height balance should be one", balance.compareTo(BigDecimal.ONE) == 0);

			// Orphan blocks to before initial 1 QORT payment
			BlockUtils.orphanBlocks(repository, 10 + 5);

			// Re-check balance from (now) invalid height
			accountBalanceData = repository.getAccountRepository().getBalance(recipientAccount.getAddress(), Asset.QORT, height - 2);
			assertNull("recipient's invalid-height balance data should be null", accountBalanceData);
		}
	}

	private void pay(Repository repository, PrivateKeyAccount sendingAccount, Account recipientAccount, BigDecimal amount) throws DataException {
		byte[] reference = sendingAccount.getLastReference();
		long timestamp = repository.getTransactionRepository().fromSignature(reference).getTimestamp() + 1;

		int txGroupId = 0;
		BigDecimal fee = BlockChain.getInstance().getUnitFee();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, sendingAccount.getPublicKey(), fee, null);
		TransactionData transactionData = new PaymentTransactionData(baseTransactionData, recipientAccount.getAddress(), amount);

		TransactionUtils.signAndMint(repository, transactionData, sendingAccount);
	}

}