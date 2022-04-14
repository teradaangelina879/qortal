package org.qortal.test.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.*;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.store.BlockStoreException;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.Litecoin;
import org.qortal.crosschain.PirateChain;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class PirateChainTests extends Common {

	private PirateChain pirateChain;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings(); // TestNet3
		pirateChain = PirateChain.getInstance();
	}

	@After
	public void afterTest() {
		Litecoin.resetForTesting();
		pirateChain = null;
	}

	@Test
	public void testGetMedianBlockTime() throws BlockStoreException, ForeignBlockchainException {
		long before = System.currentTimeMillis();
		System.out.println(String.format("Pirate Chain median blocktime: %d", pirateChain.getMedianBlockTime()));
		long afterFirst = System.currentTimeMillis();

		System.out.println(String.format("Pirate Chain median blocktime: %d", pirateChain.getMedianBlockTime()));
		long afterSecond = System.currentTimeMillis();

		long firstPeriod = afterFirst - before;
		long secondPeriod = afterSecond - afterFirst;

		System.out.println(String.format("1st call: %d ms, 2nd call: %d ms", firstPeriod, secondPeriod));

		assertTrue("2nd call should be quicker than 1st", secondPeriod < firstPeriod);
		assertTrue("2nd call should take less than 5 seconds", secondPeriod < 5000L);
	}

	@Test
	public void testGetCompactBlocks() throws ForeignBlockchainException {
		int startHeight = 1000000;
		int count = 20;

		long before = System.currentTimeMillis();
		List<CompactBlock> compactBlocks = pirateChain.getCompactBlocks(startHeight, count);
		long after = System.currentTimeMillis();

		System.out.println(String.format("Retrieval took: %d ms", after-before));

		for (CompactBlock block : compactBlocks) {
			System.out.println(String.format("Block height: %d, transaction count: %d", block.getHeight(), block.getVtxCount()));
		}

		assertEquals(count, compactBlocks.size());
	}

	@Test
	@Ignore(value = "Doesn't work, to be fixed later")
	public void testFindHtlcSecret() throws ForeignBlockchainException {
		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		byte[] expectedSecret = "This string is exactly 32 bytes!".getBytes();
		byte[] secret = BitcoinyHTLC.findHtlcSecret(pirateChain, p2shAddress);

		assertNotNull("secret not found", secret);
		assertTrue("secret incorrect", Arrays.equals(expectedSecret, secret));
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testBuildSpend() {
		String xprv58 = "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ";

		String recipient = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";
		long amount = 1000L;

		Transaction transaction = pirateChain.buildSpend(xprv58, recipient, amount);
		assertNotNull("insufficient funds", transaction);

		// Check spent key caching doesn't affect outcome

		transaction = pirateChain.buildSpend(xprv58, recipient, amount);
		assertNotNull("insufficient funds", transaction);
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testGetWalletBalance() throws ForeignBlockchainException {
		String xprv58 = "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ";

		Long balance = pirateChain.getWalletBalance(xprv58);

		assertNotNull(balance);

		System.out.println(pirateChain.format(balance));

		// Check spent key caching doesn't affect outcome

		Long repeatBalance = pirateChain.getWalletBalance(xprv58);

		assertNotNull(repeatBalance);

		System.out.println(pirateChain.format(repeatBalance));

		assertEquals(balance, repeatBalance);
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testGetUnusedReceiveAddress() throws ForeignBlockchainException {
		String xprv58 = "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ";

		String address = pirateChain.getUnusedReceiveAddress(xprv58);

		assertNotNull(address);

		System.out.println(address);
	}

}
