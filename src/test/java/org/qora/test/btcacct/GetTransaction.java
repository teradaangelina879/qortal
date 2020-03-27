package org.qora.test.btcacct;

import java.security.Security;
import java.util.List;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.TransactionOutput;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.qora.crosschain.BTC;
import org.qora.settings.Settings;

import com.google.common.hash.HashCode;

public class GetTransaction {

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: GetTransaction <bitcoin-tx>"));
		System.err.println(String.format("example: GetTransaction 816407e79568f165f13e09e9912c5f2243e0a23a007cec425acedc2e89284660 (mainnet)"));
		System.err.println(String.format("example: GetTransaction 3bfd17a492a4e3d6cb7204e17e20aca6c1ab82e1828bd1106eefbaf086fb8a4e (testnet)"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 1 || args.length > 1)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Settings.fileInstance("settings-test.json");

		byte[] transactionId = null;

		try {
			int argIndex = 0;

			transactionId = HashCode.fromString(args[argIndex++]).asBytes();
		} catch (NumberFormatException | AddressFormatException e) {
			usage(String.format("Argument format exception: %s", e.getMessage()));
		}

		// Chain replay start time
		long startTime = (System.currentTimeMillis() / 1000L) - 14 * 24 * 60 * 60; // 14 days before now, in seconds

		// Grab all outputs from transaction
		List<TransactionOutput> fundingOutputs = BTC.getInstance().getOutputs(transactionId, startTime);
		if (fundingOutputs == null) {
			System.out.println(String.format("Transaction not found"));
			return;
		}

		System.out.println(String.format("Found %d output%s", fundingOutputs.size(), (fundingOutputs.size() != 1 ? "s" : "")));

		for (TransactionOutput fundingOutput : fundingOutputs)
			System.out.println(String.format("Output %d: %s", fundingOutput.getIndex(), fundingOutput.getValue().toPlainString()));
	}

}
