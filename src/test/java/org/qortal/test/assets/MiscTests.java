package org.qortal.test.assets;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;
import org.qortal.utils.Amounts;

public class MiscTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testCalcCommitmentWithRoundUp() throws DataException {
		long amount = 1234_87654321L;
		long price = 1_35615263L;

		// 1234.87654321 * 1.35615263 = 1674.6810717995501423
		// rounded up to 8dp gives: 1674.68107180
		long expectedCommitment = 1674_68107180L;

		long actualCommitment = Amounts.roundUpScaledMultiply(amount, price);
		assertEquals(expectedCommitment, actualCommitment);
	}

	@Test
	public void testCalcCommitmentWithoutRoundUp() throws DataException {
		long amount = 1234_87650000L;
		long price = 1_35610000L;

		// 1234.87650000 * 1.35610000 = 1674.6160216500000000
		// rounded up to 8dp gives: 1674.61602165
		long expectedCommitment = 1674_61602165L;

		long actualCommitment = Amounts.roundUpScaledMultiply(amount, price);
		assertEquals(expectedCommitment, actualCommitment);
	}

}
