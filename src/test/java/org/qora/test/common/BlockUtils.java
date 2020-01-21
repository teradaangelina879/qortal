package org.qora.test.common;

import java.math.BigDecimal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qora.account.PrivateKeyAccount;
import org.qora.block.Block;
import org.qora.block.BlockChain;
import org.qora.block.BlockMinter;
import org.qora.data.block.BlockData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;

public class BlockUtils {

	private static final Logger LOGGER = LogManager.getLogger(BlockUtils.class);

	/** Mints a new block using "alice-reward-share" test account. */
	public static void mintBlock(Repository repository) throws DataException {
		PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice-reward-share");
		BlockMinter.mintTestingBlock(repository, mintingAccount);
	}

	public static BigDecimal getNextBlockReward(Repository repository) throws DataException {
		int currentHeight = repository.getBlockRepository().getBlockchainHeight();

		return BlockChain.getInstance().getRewardAtHeight(currentHeight + 1);
	}

	public static void orphanLastBlock(Repository repository) throws DataException {
		BlockData blockData = repository.getBlockRepository().getLastBlock();

		final int height = blockData.getHeight();

		Block block = new Block(repository, blockData);
		block.orphan();

		LOGGER.info(String.format("Orphaned block: %d", height));

		repository.saveChanges();
	}

	public static void orphanBlocks(Repository repository, int count) throws DataException {
		for (int i = 0; i < count; ++i)
			orphanLastBlock(repository);
	}

	public static void orphanToBlock(Repository repository, int targetHeight) throws DataException {
		do {
			BlockData blockData = repository.getBlockRepository().getLastBlock();
			final int height = blockData.getHeight();

			if (height <= targetHeight)
				return;

			Block block = new Block(repository, blockData);
			block.orphan();

			LOGGER.info(String.format("Orphaned block: %d", height));

			repository.saveChanges();
		} while (true);
	}

}
