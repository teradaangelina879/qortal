package org.qortal.test.btcacct;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.wallet.WalletTransaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.crosschain.BTC;
import org.qortal.crosschain.BTCACCT;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

public class BtcTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings(); // TestNet3
	}

	@After
	public void afterTest() {
		BTC.resetForTesting();
	}

	@Test
	public void testStartupShutdownTestNet3() {
		BTC btc = BTC.getInstance();

		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Long> future = executor.submit(() -> btc.getMedianBlockTime());

		BTC.shutdown();

		try {
			Long medianBlockTime = future.get();
			assertNull("Shutdown should occur before we get a result", medianBlockTime);
		} catch (InterruptedException | ExecutionException e) {
		}
	}

	@Test
	public void testStartupShutdownRegTest() throws DataException {
		Common.useSettings("test-settings-v2-bitcoin-regtest.json");

		BTC btc = BTC.getInstance();

		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Long> future = executor.submit(() -> btc.getMedianBlockTime());

		BTC.shutdown();

		try {
			Long medianBlockTime = future.get();
			assertNull("Shutdown should occur before we get a result", medianBlockTime);
		} catch (InterruptedException | ExecutionException e) {
		}
	}

	@Test
	public void testGetMedianBlockTime() throws BlockStoreException {
		System.out.println(String.format("Starting BTC instance..."));
		BTC btc = BTC.getInstance();
		System.out.println(String.format("BTC instance started"));

		long before = System.currentTimeMillis();
		System.out.println(String.format("Bitcoin median blocktime: %d", btc.getMedianBlockTime()));
		long afterFirst = System.currentTimeMillis();

		System.out.println(String.format("Bitcoin median blocktime: %d", btc.getMedianBlockTime()));
		long afterSecond = System.currentTimeMillis();

		long firstPeriod = afterFirst - before;
		long secondPeriod = afterSecond - afterFirst;

		System.out.println(String.format("1st call: %d ms, 2nd call: %d ms", firstPeriod, secondPeriod));

		assertTrue("2nd call should be quicker than 1st", secondPeriod < firstPeriod);
		assertTrue("2nd call should take less than 5 seconds", secondPeriod < 5000L);
	}

	@Test
	public void testFindP2shSecret() {
		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";
		int startTime = 1587510000; // Tue 21 Apr 2020 23:00:00 UTC

		List<WalletTransaction> walletTransactions = new ArrayList<>();

		BTC.getInstance().getBalanceAndOtherInfo(p2shAddress, startTime, null, walletTransactions);

		byte[] expectedSecret = AtTests.secret;
		byte[] secret = BTCACCT.findP2shSecret(p2shAddress, walletTransactions);

		assertNotNull(secret);
		assertTrue("secret incorrect", Arrays.equals(expectedSecret, secret));
	}

}
