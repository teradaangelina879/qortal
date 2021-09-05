package org.qortal.block;

import org.qortal.naming.Name;
import org.qortal.repository.DataException;

public final class Block536140 {

	private Block536140() {
		/* Do not instantiate */
	}

	public static void processFix(Block block) throws DataException {
		// Unregister the existing name record if it exists
		// This ensures that the duplicate name is considered valid, and therefore
		// the second (i.e. duplicate) REGISTER_NAME transaction data is applied.
		// Both were issued by the same user account, so there is no conflict.
		Name name = new Name(block.repository, "Qweb");
		name.unregister();
	}

}
