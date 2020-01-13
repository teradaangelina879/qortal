package org.qora.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qora.block.BlockChain;
import org.qora.data.account.AccountData;
import org.qora.data.transaction.BaseTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.data.transaction.TransferPrivsTransactionData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.test.common.BlockUtils;
import org.qora.test.common.Common;
import org.qora.test.common.TestAccount;
import org.qora.test.common.TransactionUtils;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.List;

public class TransferPrivsTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testAliceIntoDilbertTransferPrivs() throws DataException {
		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			AccountData initialAliceData = repository.getAccountRepository().getAccount(alice.getAddress());

			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			AccountData initialDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());

			// Blocks needed by Alice to get Dilbert to next level post-combine
			final int expectedPostCombineLevel = initialDilbertData.getLevel() + 1;
			final int blocksNeeded = cumulativeBlocksByLevel.get(expectedPostCombineLevel) - initialDilbertData.getBlocksMinted() - initialDilbertData.getBlocksMintedAdjustment();

			// Level we expect Alice to reach after minting above blocks
			int expectedLevel = 0;
			for (int newLevel = maximumLevel; newLevel > 0; --newLevel)
				if (blocksNeeded >= cumulativeBlocksByLevel.get(newLevel)) {
					expectedLevel = newLevel;
					break;
				}

			// Mint enough blocks to bump recipient level when we combine accounts
			for (int bc = 0; bc < blocksNeeded; ++bc)
				BlockUtils.mintBlock(repository);

			// Check minting account has gained level
			assertEquals("minter level incorrect", expectedLevel, (int) alice.getLevel());

			// Grab pre-combine versions of Alice and Dilbert data
			AccountData preCombineAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData preCombineDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());
			assertEquals(expectedLevel, preCombineAliceData.getLevel());

			// Combine Alice into Dilbert
			byte[] reference = alice.getLastReference();
			long timestamp = repository.getTransactionRepository().fromSignature(reference).getTimestamp() + 1;
			int txGroupId = 0;
			BigDecimal fee = BigDecimal.ONE.setScale(8);

			BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, alice.getPublicKey(), fee, null);
			TransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, dilbert.getAddress());

			TransactionUtils.signAndMint(repository, transactionData, alice);

			AccountData newAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData newDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());

			checkSenderCleared(newAliceData);

			// Confirm recipient has bumped level
			assertEquals("recipient's level incorrect", expectedPostCombineLevel, newDilbertData.getLevel());

			// Confirm recipient has gained sender's flags
			assertEquals("recipient's flags should be changed", initialAliceData.getFlags() | initialDilbertData.getFlags(), (int) newDilbertData.getFlags());

			// Confirm recipient has increased minted block count
			assertEquals("recipient minted block count incorrect", initialDilbertData.getBlocksMinted() + initialAliceData.getBlocksMinted() + blocksNeeded + 1, newDilbertData.getBlocksMinted());

			// Confirm recipient has increased minted block adjustment
			assertEquals("recipient minted block adjustment incorrect", initialDilbertData.getBlocksMintedAdjustment() + initialAliceData.getBlocksMintedAdjustment(), newDilbertData.getBlocksMintedAdjustment());

			// Orphan previous block
			BlockUtils.orphanLastBlock(repository);

			// Sender checks...
			AccountData orphanedAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			checkAccountDataRestored("sender", preCombineAliceData, orphanedAliceData);

			// Recipient checks...
			AccountData orphanedDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());
			checkAccountDataRestored("recipient", preCombineDilbertData, orphanedDilbertData);
		}
	}

	@Test
	public void testDilbertIntoAliceTransferPrivs() throws DataException {
		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			AccountData initialAliceData = repository.getAccountRepository().getAccount(alice.getAddress());

			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			AccountData initialDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());

			// Blocks needed by Alice to get Alice to next level post-combine
			final int expectedPostCombineLevel = initialDilbertData.getLevel() + 1;
			final int blocksNeeded = cumulativeBlocksByLevel.get(expectedPostCombineLevel) - initialDilbertData.getBlocksMinted() - initialDilbertData.getBlocksMintedAdjustment();

			// Level we expect Alice to reach after minting above blocks
			int expectedLevel = 0;
			for (int newLevel = maximumLevel; newLevel > 0; --newLevel)
				if (blocksNeeded >= cumulativeBlocksByLevel.get(newLevel)) {
					expectedLevel = newLevel;
					break;
				}

			// Mint enough blocks to bump recipient level when we combine accounts
			for (int bc = 0; bc < blocksNeeded; ++bc)
				BlockUtils.mintBlock(repository);

			// Check minting account has gained level
			assertEquals("minter level incorrect", expectedLevel, (int) alice.getLevel());

			// Grab pre-combine versions of Alice and Dilbert data
			AccountData preCombineAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData preCombineDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());
			assertEquals(expectedLevel, preCombineAliceData.getLevel());

			// Combine Dilbert into Alice
			byte[] reference = dilbert.getLastReference();
			long timestamp = repository.getTransactionRepository().fromSignature(reference).getTimestamp() + 1;
			int txGroupId = 0;
			BigDecimal fee = BigDecimal.ONE.setScale(8);

			BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, dilbert.getPublicKey(), fee, null);
			TransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, alice.getAddress());

			TransactionUtils.signAndMint(repository, transactionData, dilbert);

			AccountData newAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData newDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());

			checkSenderCleared(newDilbertData);

			// Confirm recipient has bumped level
			assertEquals("recipient's level incorrect", expectedPostCombineLevel, newAliceData.getLevel());

			// Confirm recipient has gained sender's flags
			assertEquals("recipient's flags should be changed", initialAliceData.getFlags() | initialDilbertData.getFlags(), (int) newAliceData.getFlags());

			// Confirm recipient has increased minted block count
			assertEquals("recipient minted block count incorrect", initialDilbertData.getBlocksMinted() + initialAliceData.getBlocksMinted() + blocksNeeded + 1, newAliceData.getBlocksMinted());

			// Confirm recipient has increased minted block adjustment
			assertEquals("recipient minted block adjustment incorrect", initialDilbertData.getBlocksMintedAdjustment() + initialAliceData.getBlocksMintedAdjustment(), newAliceData.getBlocksMintedAdjustment());

			// Orphan previous block
			BlockUtils.orphanLastBlock(repository);

			// Sender checks...
			AccountData orphanedDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());
			checkAccountDataRestored("sender", preCombineDilbertData, orphanedDilbertData);

			// Recipient checks...
			AccountData orphanedAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			checkAccountDataRestored("recipient", preCombineAliceData, orphanedAliceData);
		}
	}

	private void checkSenderCleared(AccountData senderAccountData) {
		// Confirm sender has zeroed flags
		assertEquals("sender's flags should be zeroed", 0, (int) senderAccountData.getFlags());

		// Confirm sender has zeroed level
		assertEquals("sender's level should be zeroed", 0, (int) senderAccountData.getLevel());

		// Confirm sender has zeroed minted block count
		assertEquals("sender's minted block count should be zeroed", 0, (int) senderAccountData.getBlocksMinted());

		// Confirm sender has zeroed minted block adjustment
		assertEquals("sender's minted block adjustment should be zeroed", 0, (int) senderAccountData.getBlocksMintedAdjustment());
	}

	private void checkAccountDataRestored(String accountName, AccountData expectedAccountData, AccountData actualAccountData) {
		// Confirm flags have been restored
		assertEquals(accountName + "'s flags weren't restored", expectedAccountData.getFlags(), actualAccountData.getFlags());

		// Confirm minted blocks count
		assertEquals(accountName + "'s minted block count wasn't restored", expectedAccountData.getBlocksMinted(), actualAccountData.getBlocksMinted());

		// Confirm minted block adjustment
		assertEquals(accountName + "'s minted block adjustment wasn't restored", expectedAccountData.getBlocksMintedAdjustment(), actualAccountData.getBlocksMintedAdjustment());

		// Confirm level has been restored
		assertEquals(accountName + "'s level wasn't restored", expectedAccountData.getLevel(), actualAccountData.getLevel());
	}

}
