package org.qortal.test.minting;

import static org.junit.Assert.*;

import java.math.BigDecimal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockMinter;
import org.qortal.data.account.RewardShareData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;

public class BlocksMintedCountTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testNonSelfShare() throws DataException {
		final BigDecimal sharePercent = new BigDecimal("12.8");

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create reward-share
			byte[] testRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", sharePercent);
			PrivateKeyAccount testRewardShareAccount = new PrivateKeyAccount(repository, testRewardSharePrivateKey);

			// Confirm reward-share info set correctly
			RewardShareData testRewardShareData = repository.getAccountRepository().getRewardShare(testRewardShareAccount.getPublicKey());
			assertNotNull(testRewardShareData);

			testRewardShare(repository, testRewardShareAccount, +1, +1);
		}
	}

	@Test
	public void testSelfShare() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount testRewardShareAccount = Common.getTestAccount(repository, "alice-reward-share");

			// Confirm reward-share exists
			RewardShareData testRewardShareData = repository.getAccountRepository().getRewardShare(testRewardShareAccount.getPublicKey());
			assertNotNull(testRewardShareData);

			testRewardShare(repository, testRewardShareAccount, +1, 0);
		}
	}

	private void testRewardShare(Repository repository, PrivateKeyAccount mintingAccount, int aliceDelta, int bobDelta) throws DataException {
		// Fetch pre-mint blocks minted counts
		int alicePreMintCount = getBlocksMinted(repository, "alice");
		int bobPreMintCount = getBlocksMinted(repository, "bob");

		// Mint another block
		BlockMinter.mintTestingBlock(repository, mintingAccount);

		// Fetch post-mint blocks minted counts
		int alicePostMintCount = getBlocksMinted(repository, "alice");
		int bobPostMintCount = getBlocksMinted(repository, "bob");

		// Check both accounts
		assertEquals("Alice's post-mint blocks-minted count incorrect", alicePreMintCount + aliceDelta, alicePostMintCount);
		assertEquals("Bob's post-mint blocks-minted count incorrect", bobPreMintCount + bobDelta, bobPostMintCount);

		// Orphan latest block
		BlockUtils.orphanLastBlock(repository);

		// Fetch post-orphan blocks minted counts
		int alicePostOrphanCount = getBlocksMinted(repository, "alice");
		int bobPostOrphanCount = getBlocksMinted(repository, "bob");

		// Check blocks minted counts reverted correctly
		assertEquals("Alice's post-orphan blocks-minted count incorrect", alicePreMintCount, alicePostOrphanCount);
		assertEquals("Bob's post-orphan blocks-minted count incorrect", bobPreMintCount, bobPostOrphanCount);
	}

	private int getBlocksMinted(Repository repository, String name) throws DataException {
		TestAccount testAccount = Common.getTestAccount(repository, name);
		return repository.getAccountRepository().getAccount(testAccount.getAddress()).getBlocksMinted();
	}

}
