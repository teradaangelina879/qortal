package org.qortal.test.crosschain;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.crosschain.Bitcoin;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

public class HtlcTests extends Common {

	private Bitcoin bitcoin;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings(); // TestNet3
		bitcoin = Bitcoin.getInstance();
	}

	@After
	public void afterTest() {
		Bitcoin.resetForTesting();
		bitcoin = null;
	}

	@Test
	public void testFindHtlcSecret() throws ForeignBlockchainException {
		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		List<byte[]> rawTransactions = bitcoin.getAddressTransactions(p2shAddress);

		byte[] expectedSecret = "This string is exactly 32 bytes!".getBytes();
		byte[] secret = BitcoinyHTLC.findHtlcSecret(bitcoin.getNetworkParameters(), p2shAddress, rawTransactions);

		assertNotNull(secret);
		assertTrue("secret incorrect", Arrays.equals(expectedSecret, secret));
	}

	@Test
	public void testDetermineHtlcStatus() throws ForeignBlockchainException {
		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		BitcoinyHTLC.Status htlcStatus = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddress, 1L);
		assertNotNull(htlcStatus);

		System.out.println(String.format("HTLC %s status: %s", p2shAddress, htlcStatus.name()));
	}

}
