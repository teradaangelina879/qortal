package org.qortal.test.assets;

import org.junit.After;
import org.junit.Before;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

public class MixedPricingTests extends Common{

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-old-asset.json");
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	/**
	 * Check order matching between 'old' pricing order and 'new' pricing order.
	 * <p>
	 * In this test, the order created under 'old' pricing scheme has
	 * "amount" in have-asset?
	 */

}
