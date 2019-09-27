package org.qora.account;

import org.qora.block.BlockChain;
import org.qora.repository.DataException;
import org.qora.utils.BitTwiddling;

/** Relating to whether accounts can forge. */
public class Forging {

	/** Returns mask for account flags for forging bits. */
	public static int getForgingMask() {
		return BitTwiddling.calcMask(BlockChain.getInstance().getForgingTiers().size() - 1);
	}

	public static boolean canForge(Account account) throws DataException {
		Integer level = account.getLevel();
		return level != null && level > 0;
	}

}
