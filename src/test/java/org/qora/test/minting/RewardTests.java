package org.qora.test.minting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qora.account.PrivateKeyAccount;
import org.qora.asset.Asset;
import org.qora.block.BlockChain;
import org.qora.block.BlockChain.RewardByHeight;
import org.qora.block.BlockMinter;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.test.common.AccountUtils;
import org.qora.test.common.BlockUtils;
import org.qora.test.common.Common;

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
			Map<String, Map<Long, BigDecimal>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);

			PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice");

			BigDecimal blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, mintingAccount);

			BigDecimal expectedBalance = initialBalances.get("alice").get(Asset.QORT).add(blockReward);
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, expectedBalance);
		}
	}

	@Test
	public void testRewards() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, BigDecimal>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);

			PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice");

			List<RewardByHeight> rewards = BlockChain.getInstance().getBlockRewardsByHeight();

			int rewardIndex = rewards.size() - 1;

			RewardByHeight rewardInfo = rewards.get(rewardIndex);
			BigDecimal expectedBalance = initialBalances.get("alice").get(Asset.QORT);

			for (int height = rewardInfo.height; height > 1; --height) {
				if (height < rewardInfo.height) {
					--rewardIndex;
					rewardInfo = rewards.get(rewardIndex);
				}

				BlockMinter.mintTestingBlock(repository, mintingAccount);
				expectedBalance = expectedBalance.add(rewardInfo.reward);
			}

			AccountUtils.assertBalance(repository, "alice", Asset.QORT, expectedBalance);
		}
	}

	@Test
	public void testRewardSharing() throws DataException {
		final BigDecimal share = new BigDecimal("12.8");

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", share);
			PrivateKeyAccount rewardShareAccount = new PrivateKeyAccount(repository, rewardSharePrivateKey);

			Map<String, Map<Long, BigDecimal>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);
			BigDecimal blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, rewardShareAccount);

			// We're expecting reward * 12.8% to Bob, the rest to Alice

			BigDecimal bobShare = blockReward.multiply(share.movePointLeft(2)).setScale(8, RoundingMode.DOWN);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, initialBalances.get("bob").get(Asset.QORT).add(bobShare));

			BigDecimal aliceShare = blockReward.subtract(bobShare);
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, initialBalances.get("alice").get(Asset.QORT).add(aliceShare));
		}
	}

}