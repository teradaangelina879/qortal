package org.qortal.test.btcacct;

import static org.junit.Assert.*;

import java.security.Security;
import java.util.List;

import org.bitcoinj.core.Address;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.ScriptBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.Test;
import org.qortal.crosschain.ElectrumX;
import org.qortal.utils.BitTwiddling;
import org.qortal.utils.Pair;

import com.google.common.hash.HashCode;

public class ElectrumXTests {

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);
	}

	@Test
	public void testInstance() {
		ElectrumX electrumX = ElectrumX.getInstance("TEST3");
		assertNotNull(electrumX);
	}

	@Test
	public void testGetCurrentHeight() {
		ElectrumX electrumX = ElectrumX.getInstance("TEST3");

		Integer height = electrumX.getCurrentHeight();

		assertNotNull(height);
		assertTrue(height > 10000);
		System.out.println("Current TEST3 height: " + height);
	}

	@Test
	public void testGetRecentBlocks() {
		ElectrumX electrumX = ElectrumX.getInstance("TEST3");

		Integer height = electrumX.getCurrentHeight();
		assertNotNull(height);
		assertTrue(height > 10000);

		List<byte[]> recentBlockHeaders = electrumX.getBlockHeaders(height - 11, 11);
		assertNotNull(recentBlockHeaders);

		System.out.println(String.format("Returned %d recent blocks", recentBlockHeaders.size()));
		for (int i = 0; i < recentBlockHeaders.size(); ++i) {
			byte[] blockHeader = recentBlockHeaders.get(i);

			// Timestamp(int) is at 4 + 32 + 32 = 68 bytes offset
			int offset = 4 + 32 + 32;
			int timestamp = BitTwiddling.fromLEBytes(blockHeader, offset);
			System.out.println(String.format("Block %d timestamp: %d", height + i, timestamp));
		}
	}

	@Test
	public void testGetP2PKHBalance() {
		ElectrumX electrumX = ElectrumX.getInstance("TEST3");

		Address address = Address.fromString(TestNet3Params.get(), "n3GNqMveyvaPvUbH469vDRadqpJMPc84JA");
		byte[] script = ScriptBuilder.createOutputScript(address).getProgram();
		Long balance = electrumX.getBalance(script);

		assertNotNull(balance);
		assertTrue(balance > 0L);

		System.out.println(String.format("TestNet address %s has balance: %d sats / %d.%08d BTC", address, balance, (balance / 100000000L), (balance % 100000000L)));
	}

	@Test
	public void testGetP2SHBalance() {
		ElectrumX electrumX = ElectrumX.getInstance("TEST3");

		Address address = Address.fromString(TestNet3Params.get(), "2N4szZUfigj7fSBCEX4PaC8TVbC5EvidaVF");
		byte[] script = ScriptBuilder.createOutputScript(address).getProgram();
		Long balance = electrumX.getBalance(script);

		assertNotNull(balance);
		assertTrue(balance > 0L);

		System.out.println(String.format("TestNet address %s has balance: %d sats / %d.%08d BTC", address, balance, (balance / 100000000L), (balance % 100000000L)));
	}

	@Test
	public void testGetUnspentOutputs() {
		ElectrumX electrumX = ElectrumX.getInstance("TEST3");

		Address address = Address.fromString(TestNet3Params.get(), "2N4szZUfigj7fSBCEX4PaC8TVbC5EvidaVF");
		byte[] script = ScriptBuilder.createOutputScript(address).getProgram();
		List<Pair<byte[], Integer>> unspentOutputs = electrumX.getUnspentOutputs(script);

		assertNotNull(unspentOutputs);
		assertFalse(unspentOutputs.isEmpty());

		for (Pair<byte[], Integer> unspentOutput : unspentOutputs)
			System.out.println(String.format("TestNet address %s has unspent output at tx %s, output index %d", address, HashCode.fromBytes(unspentOutput.getA()).toString(), unspentOutput.getB()));
	}

	@Test
	public void testGetRawTransaction() {
		ElectrumX electrumX = ElectrumX.getInstance("TEST3");

		byte[] txHash = HashCode.fromString("7653fea9ffcd829d45ed2672938419a94951b08175982021e77d619b553f29af").asBytes();

		byte[] rawTransactionBytes = electrumX.getRawTransaction(txHash);

		assertNotNull(rawTransactionBytes);
	}

	@Test
	public void testGetAddressTransactions() {
		ElectrumX electrumX = ElectrumX.getInstance("TEST3");

		Address address = Address.fromString(TestNet3Params.get(), "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE");
		byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

		List<byte[]> rawTransactions = electrumX.getAddressTransactions(script);

		assertNotNull(rawTransactions);
		assertFalse(rawTransactions.isEmpty());
	}

}
