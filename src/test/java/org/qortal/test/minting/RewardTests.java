package org.qortal.test.minting;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bitcoinj.core.Base58;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.block.BlockMinter;
import org.qortal.block.BlockChain.RewardByHeight;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.utils.Amounts;

public class RewardTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testSimpleReward() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);

			Long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockUtils.mintBlock(repository);

			long expectedBalance = initialBalances.get("alice").get(Asset.QORT) + blockReward;
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, expectedBalance);
		}
	}

	@Test
	public void testRewards() throws DataException {
		List<RewardByHeight> rewardsByHeight = BlockChain.getInstance().getBlockRewardsByHeight();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);

			int rewardIndex = rewardsByHeight.size() - 1;

			RewardByHeight rewardInfo = rewardsByHeight.get(rewardIndex);
			Long expectedBalance = initialBalances.get("alice").get(Asset.QORT);

			for (int height = rewardInfo.height; height > 1; --height) {
				if (height < rewardInfo.height) {
					--rewardIndex;
					rewardInfo = rewardsByHeight.get(rewardIndex);
				}

				BlockUtils.mintBlock(repository);

				expectedBalance += rewardInfo.unscaledReward;
			}

			AccountUtils.assertBalance(repository, "alice", Asset.QORT, expectedBalance);
		}
	}

	@Test
	public void testRewardSharing() throws DataException {
		final int share = 12_80; // 12.80%

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", share);
			PrivateKeyAccount rewardShareAccount = new PrivateKeyAccount(repository, rewardSharePrivateKey);

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);
			Long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, rewardShareAccount);

			// We're expecting reward * 12.8% to Bob, the rest to Alice

			long bobShare = (blockReward * share) / 100L / 100L;
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, initialBalances.get("bob").get(Asset.QORT) + bobShare);

			long aliceShare = blockReward - bobShare;
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, initialBalances.get("alice").get(Asset.QORT) + aliceShare);
		}
	}


	@Test
	public void testLegacyQoraReward() throws DataException {
		Common.useSettings("test-settings-v2-qora-holder.json");

		long qoraHoldersShare = BlockChain.getInstance().getQoraHoldersUnscaledShare();
		long qoraPerQort = BlockChain.getInstance().getUnscaledQoraPerQortReward();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);

			Long blockReward = BlockUtils.getNextBlockReward(repository);

			// Fetch all legacy QORA holder balances
			List<AccountBalanceData> qoraHolders = repository.getAccountRepository().getAssetBalances(Asset.LEGACY_QORA, true);
			long totalQoraHeld = 0L;
			for (AccountBalanceData accountBalanceData : qoraHolders)
				totalQoraHeld += accountBalanceData.getBalance();

			BlockUtils.mintBlock(repository);

			/*
			 * Example:
			 * 
			 * Block reward is 100 QORT, QORA-holders' share is 0.20 (20%) = 20 QORT
			 * 
			 * We hold 100 QORA
			 * Someone else holds 28 QORA
			 * Total QORA held: 128 QORA
			 * 
			 * Our portion of that is 100 QORA / 128 QORA * 20 QORT = 15.625 QORT
			 * 
			 * QORA holders earn at most 1 QORT per 250 QORA held.
			 * 
			 * So we can earn at most 100 QORA / 250 QORAperQORT = 0.4 QORT
			 * 
			 * Thus our block earning should be capped to 0.4 QORT.
			 */

			// Expected reward
			long qoraHoldersReward = (blockReward * qoraHoldersShare) / Amounts.MULTIPLIER;
			assertTrue("QORA-holders share of block reward should be less than total block reward", qoraHoldersReward < blockReward);

			long ourQoraHeld = initialBalances.get("chloe").get(Asset.LEGACY_QORA);
			long ourQoraReward = (qoraHoldersReward * ourQoraHeld) / totalQoraHeld;
			assertTrue("Our QORA-related reward should be less than total QORA-holders share of block reward", ourQoraReward < qoraHoldersReward);

			long ourQortFromQoraCap = ourQoraHeld / qoraPerQort;

			long expectedReward = Math.min(ourQoraReward, ourQortFromQoraCap);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, initialBalances.get("chloe").get(Asset.QORT) + expectedReward);

			AccountUtils.assertBalance(repository, "chloe", Asset.QORT_FROM_QORA, initialBalances.get("chloe").get(Asset.QORT_FROM_QORA) + expectedReward);
		}
	}

	@Test
	public void testMaxLegacyQoraReward() throws DataException {
		Common.useSettings("test-settings-v2-qora-holder.json");

		long qoraPerQort = BlockChain.getInstance().getUnscaledQoraPerQortReward();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);

			// Mint lots of blocks
			for (int i = 0; i < 100; ++i)
				BlockUtils.mintBlock(repository);

			// Expected balances to be limited by Chloe's legacy QORA amount
			long expectedBalance = initialBalances.get("chloe").get(Asset.LEGACY_QORA) / qoraPerQort;
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, initialBalances.get("chloe").get(Asset.QORT) + expectedBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT_FROM_QORA, initialBalances.get("chloe").get(Asset.QORT_FROM_QORA) + expectedBalance);
		}
	}

	/** Use Alice-Chloe reward-share to bump Chloe from level 0 to level 1, then check orphaning works as expected. */
	@Test
	public void testLevel1() throws DataException {
		List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			assertEquals(0, (int) chloe.getLevel());

			// Alice needs to mint block containing REWARD_SHARE BEFORE Alice loses minting privs
			byte[] aliceChloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "chloe", 0); // Block minted by Alice
			PrivateKeyAccount aliceChloeRewardShareAccount = new PrivateKeyAccount(repository, aliceChloeRewardSharePrivateKey);

			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(1);
			// Mint enough blocks to bump testAccount level
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, aliceChloeRewardShareAccount);

			assertEquals(1, (int) chloe.getLevel());

			// Orphan back to genesis block
			BlockUtils.orphanToBlock(repository, 1);

			assertEquals(0, (int) chloe.getLevel());
		}
	}

	/** Test rewards to founders, one in reward-share, the other is self-share. */
	@Test
	public void testFounderRewards() throws DataException {
		Common.useSettings("test-settings-v2-founder-rewards.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			Long blockReward = BlockUtils.getNextBlockReward(repository);

			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice to mint, therefore online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share NOT online

			// Chloe self-share and reward-share with Dilbert both online
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");
			mintingAndOnlineAccounts.add(chloeSelfShare);

			PrivateKeyAccount chloeDilbertRewardShare = new PrivateKeyAccount(repository, Base58.decode("HuiyqLipUN1V9p1HZfLhyEwmEA6BTaT2qEfjgkwPViV4"));
			mintingAndOnlineAccounts.add(chloeDilbertRewardShare);

			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// 3 founders (online or not) so blockReward divided by 3
			int founderCount = 3;
			long perFounderReward = blockReward / founderCount;

			// Alice simple self-share so her reward is perFounderReward
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, perFounderReward);

			// Bob not online so his reward is simply perFounderReward
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, perFounderReward);

			// Chloe has two reward-shares, so her reward is divided by 2
			int chloeSharesCount = 2;
			long chloePerShareReward = perFounderReward / chloeSharesCount;

			// Her self-share gets chloePerShareReward
			long chloeExpectedBalance = chloePerShareReward;

			// Her reward-share with Dilbert: 25% goes to Dilbert
			int dilbertSharePercent = 25;
			long dilbertExpectedBalance = (chloePerShareReward * dilbertSharePercent) / 100L;

			// The remaining 75% goes to Chloe
			long rewardShareRemaining = chloePerShareReward - dilbertExpectedBalance;
			chloeExpectedBalance += rewardShareRemaining;
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeExpectedBalance);
		}
	}

}