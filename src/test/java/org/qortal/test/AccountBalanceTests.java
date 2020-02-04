package org.qortal.test;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.AccountRepository.BalanceOrdering;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;

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
			assertEqualBigDecimals("Post-orphan balance should match initial", initialBalance, orphanedBalance);
		}
	}

	/** Tests we can fetch initial balance when newer balance exists. */
	@Test
	public void testGetBalanceAtHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			BigDecimal initialBalance = testNewerBalance(repository, alice);

			// Fetch all historic balances
			List<AccountBalanceData> historicBalances = repository.getAccountRepository().getHistoricBalances(alice.getAddress(), Asset.QORT);
			for (AccountBalanceData historicBalance : historicBalances)
				System.out.println(String.format("Balance at height %d: %s", historicBalance.getHeight(), historicBalance.getBalance().toPlainString()));

			// Fetch balance at height 1, even though newer balance exists
			AccountBalanceData accountBalanceData = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT, 1);
			BigDecimal genesisBalance = accountBalanceData.getBalance();

			// Confirm genesis balance is same as initial
			assertEqualBigDecimals("Genesis balance should match initial", initialBalance, genesisBalance);
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
			System.out.println(String.format("Test recipient: %s", recipientAccount.getAddress()));

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			// Confirm recipient balance is zero
			BigDecimal balance = recipientAccount.getConfirmedBalance(Asset.QORT);
			assertEqualBigDecimals("recipient's balance should be zero", BigDecimal.ZERO, balance);

			// Confirm recipient has no historic balances
			List<AccountBalanceData> historicBalances = repository.getAccountRepository().getHistoricBalances(recipientAccount.getAddress(), Asset.QORT);
			for (AccountBalanceData historicBalance : historicBalances)
				System.err.println(String.format("Block %d: %s", historicBalance.getHeight(), historicBalance.getBalance().toPlainString()));
			assertTrue("recipient should not have historic balances yet", historicBalances.isEmpty());

			// Send 1 QORT to recipient
			TestAccount sendingAccount = Common.getTestAccount(repository, "alice");
			pay(repository, sendingAccount, recipientAccount, BigDecimal.ONE);

			// Mint some more blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			// Send more QORT to recipient
			BigDecimal amount = BigDecimal.valueOf(random.nextInt(123456));
			pay(repository, sendingAccount, recipientAccount, amount);
			BigDecimal totalAmount = BigDecimal.ONE.add(amount);

			// Mint some more blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			// Confirm recipient balance is as expected
			balance = recipientAccount.getConfirmedBalance(Asset.QORT);
			assertEqualBigDecimals("recipient's balance incorrect", totalAmount, balance);

			historicBalances = repository.getAccountRepository().getHistoricBalances(recipientAccount.getAddress(), Asset.QORT);
			for (AccountBalanceData historicBalance : historicBalances)
				System.out.println(String.format("Block %d: %s", historicBalance.getHeight(), historicBalance.getBalance().toPlainString()));

			// Confirm balance as of 2 blocks ago
			int height = repository.getBlockRepository().getBlockchainHeight();
			balance = repository.getAccountRepository().getBalance(recipientAccount.getAddress(), Asset.QORT, height - 2).getBalance();
			assertEqualBigDecimals("recipient's historic balance incorrect", totalAmount, balance);

			// Confirm balance prior to last payment
			balance = repository.getAccountRepository().getBalance(recipientAccount.getAddress(), Asset.QORT, height - 15).getBalance();
			assertEqualBigDecimals("recipient's historic balance incorrect", BigDecimal.ONE, balance);

			// Orphan blocks to before last payment
			BlockUtils.orphanBlocks(repository, 10 + 5);

			// Re-check balance from (now) invalid height
			AccountBalanceData accountBalanceData = repository.getAccountRepository().getBalance(recipientAccount.getAddress(), Asset.QORT, height - 2);
			balance = accountBalanceData.getBalance();
			assertEqualBigDecimals("recipient's invalid-height balance should be one", BigDecimal.ONE, balance);

			// Orphan blocks to before initial 1 QORT payment
			BlockUtils.orphanBlocks(repository, 10 + 5);

			// Re-check balance from (now) invalid height
			accountBalanceData = repository.getAccountRepository().getBalance(recipientAccount.getAddress(), Asset.QORT, height - 2);
			assertNull("recipient's invalid-height balance data should be null", accountBalanceData);

			// Confirm recipient has no historic balances
			historicBalances = repository.getAccountRepository().getHistoricBalances(recipientAccount.getAddress(), Asset.QORT);
			for (AccountBalanceData historicBalance : historicBalances)
				System.err.println(String.format("Block %d: %s", historicBalance.getHeight(), historicBalance.getBalance().toPlainString()));
			assertTrue("recipient should have no remaining historic balances", historicBalances.isEmpty());
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

	/** Tests SQL query speed for account balance fetches. */
	@Test
	public void testRepositorySpeed() throws DataException, SQLException {
		Random random = new Random();
		final long MAX_QUERY_TIME = 80L; // ms

		try (final Repository repository = RepositoryManager.getRepository()) {
			System.out.println("Creating random accounts...");

			// Generate some random accounts
			List<Account> accounts = new ArrayList<>();
			for (int ai = 0; ai < 20; ++ai) {
				byte[] publicKey = new byte[32];
				random.nextBytes(publicKey);

				PublicKeyAccount account = new PublicKeyAccount(repository, publicKey);
				accounts.add(account);

				AccountData accountData = new AccountData(account.getAddress());
				repository.getAccountRepository().ensureAccount(accountData);
			}
			repository.saveChanges();

			System.out.println("Creating random balances...");

			// Fill with lots of random balances
			for (int i = 0; i < 100000; ++i) {
				Account account = accounts.get(random.nextInt(accounts.size()));
				int assetId = random.nextInt(2);
				BigDecimal balance = BigDecimal.valueOf(random.nextInt(100000));

				AccountBalanceData accountBalanceData = new AccountBalanceData(account.getAddress(), assetId, balance);
				repository.getAccountRepository().save(accountBalanceData);

				// Maybe mint a block to change height
				if (i > 0 && (i % 1000) == 0)
					BlockUtils.mintBlock(repository);
			}
			repository.saveChanges();

			// Address filtering test cases
			List<String> testAddresses = accounts.stream().limit(3).map(account -> account.getAddress()).collect(Collectors.toList());
			List<List<String>> addressFilteringCases = Arrays.asList(null, testAddresses);

			// AssetID filtering test cases
			List<List<Long>> assetIdFilteringCases = Arrays.asList(null, Arrays.asList(0L, 1L, 2L));

			// Results ordering test cases
			List<BalanceOrdering> orderingCases = new ArrayList<>();
			orderingCases.add(null);
			orderingCases.addAll(Arrays.asList(BalanceOrdering.values()));

			// Zero exclusion test cases
			List<Boolean> zeroExclusionCases = Arrays.asList(null, true, false);

			// Limit test cases
			List<Integer> limitCases = Arrays.asList(null, 10);

			// Offset test cases
			List<Integer> offsetCases = Arrays.asList(null, 10);

			// Reverse results cases
			List<Boolean> reverseCases = Arrays.asList(null, true, false);

			repository.setDebug(true);

			// Test all cases
			for (List<String> addresses : addressFilteringCases)
				for (List<Long> assetIds : assetIdFilteringCases)
					for (BalanceOrdering balanceOrdering : orderingCases)
						for (Boolean excludeZero : zeroExclusionCases)
							for (Integer limit : limitCases)
								for (Integer offset : offsetCases)
									for (Boolean reverse : reverseCases) {
										repository.discardChanges();

										System.out.println(String.format("Testing query: %s addresses, %s assetIDs, %s ordering, %b zero-exclusion, %d limit, %d offset, %b reverse",
												(addresses == null ? "no" : "with"), (assetIds == null ? "no" : "with"), balanceOrdering, excludeZero, limit, offset, reverse));

										long before = System.currentTimeMillis();
										repository.getAccountRepository().getAssetBalances(addresses, assetIds, balanceOrdering, excludeZero, limit, offset, reverse);
										final long period = System.currentTimeMillis() - before;
										assertTrue(String.format("Query too slow: %dms", period), period < MAX_QUERY_TIME);
									}
		}

		// Rebuild repository to avoid orphan check
		Common.useDefaultSettings();
	}

}