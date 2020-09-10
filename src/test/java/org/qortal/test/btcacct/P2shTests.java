package org.qortal.test.btcacct;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.crosschain.BTC;
import org.qortal.crosschain.BTCP2SH;
import org.qortal.crosschain.BitcoinException;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

public class P2shTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings(); // TestNet3
	}

	@After
	public void afterTest() {
		BTC.resetForTesting();
	}

	@Test
	public void testFindP2shSecret() throws BitcoinException {
		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		List<byte[]> rawTransactions = BTC.getInstance().getAddressTransactions(p2shAddress);

		byte[] expectedSecret = "This string is exactly 32 bytes!".getBytes();
		byte[] secret = BTCP2SH.findP2shSecret(p2shAddress, rawTransactions);

		assertNotNull(secret);
		assertTrue("secret incorrect", Arrays.equals(expectedSecret, secret));
	}

	@Test
	public void testDetermineP2shStatus() throws BitcoinException {
		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		BTCP2SH.Status p2shStatus = BTCP2SH.determineP2shStatus(p2shAddress, 1L);

		System.out.println(String.format("P2SH %s status: %s", p2shAddress, p2shStatus.name()));
	}

}
