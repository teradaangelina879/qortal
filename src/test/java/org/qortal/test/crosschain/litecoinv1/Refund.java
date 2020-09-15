package org.qortal.test.crosschain.litecoinv1;

import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script.ScriptType;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.Litecoin;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;

import com.google.common.hash.HashCode;

public class Refund {

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: Refund <P2SH-address> <refund-PRIVATE-KEY> <redeem-P2PKH> <HASH160-of-secret> <locktime> <output-address>"));
		System.err.println(String.format("example: Refund "
				+ "2N4378NbEVGjmiUmoUD9g1vCY6kyx9tDUJ6 \\\n"
				+ "\tef8f31b49c31b4a140aebcd9605fded88cc2dad0844c4b984f9191a5a416f72d3801e16447b0 \\\n"
				+ "\tmrBpZYYGYMwUa8tRjTiXfP1ySqNXszWN5h \\\n"
				+ "\tdaf59884b4d1aec8c1b17102530909ee43c0151a \\\n"
				+ "\t1600184800 \\\n"
				+ "\tmoJtbbhs7T4Z5hmBH2iyKhGrCWBzQWS2CL"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 6 || args.length > 6)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		Settings.fileInstance("settings-test.json");

		Bitcoiny bitcoiny = Litecoin.getInstance();
		NetworkParameters params = bitcoiny.getNetworkParameters();

		Address p2shAddress = null;
		byte[] refundPrivateKey = null;
		Address redeemAddress = null;
		byte[] secretHash = null;
		int lockTime = 0;
		Address outputAddress = null;

		int argIndex = 0;
		try {
			p2shAddress = Address.fromString(params, args[argIndex++]);
			if (p2shAddress.getOutputScriptType() != ScriptType.P2SH)
				usage("P2SH address invalid");

			refundPrivateKey = HashCode.fromString(args[argIndex++]).asBytes();
			// Auto-trim
			if (refundPrivateKey.length >= 37 && refundPrivateKey.length <= 38)
				refundPrivateKey = Arrays.copyOfRange(refundPrivateKey, 1, 33);
			if (refundPrivateKey.length != 32)
				usage("Refund private key must be 32 bytes");

			redeemAddress = Address.fromString(params, args[argIndex++]);
			if (redeemAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Redeem address must be in P2PKH form");

			secretHash = HashCode.fromString(args[argIndex++]).asBytes();
			if (secretHash.length != 20)
				usage("HASH160 of secret must be 20 bytes");

			lockTime = Integer.parseInt(args[argIndex++]);

			outputAddress = Address.fromString(params, args[argIndex++]);
			if (outputAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("output address invalid");
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		Coin p2shFee;
		try {
			p2shFee = Coin.valueOf(bitcoiny.getP2shFee(null));
		} catch (ForeignBlockchainException e) {
			throw new RuntimeException(e.getMessage());
		}

		try {
			System.out.println(String.format("P2SH address: %s", p2shAddress));
			System.out.println(String.format("Refund PRIVATE key: %s", HashCode.fromBytes(refundPrivateKey)));
			System.out.println(String.format("Redeem address: %s", redeemAddress));
			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));
			System.out.println(String.format("Refund recipient (PKH): %s (%s)", outputAddress, HashCode.fromBytes(outputAddress.getHash())));

			System.out.println(String.format("Refund miner's fee: %s", bitcoiny.format(p2shFee)));

			// New/derived info

			ECKey refundKey = ECKey.fromPrivate(refundPrivateKey);
			Address refundAddress = Address.fromKey(params, refundKey, ScriptType.P2PKH);

			byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(refundAddress.getHash(), lockTime, redeemAddress.getHash(), secretHash);
			System.out.println(String.format("Redeem script: %s", HashCode.fromBytes(redeemScriptBytes)));

			byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
			Address derivedP2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			if (!derivedP2shAddress.equals(p2shAddress)) {
				System.err.println(String.format("Derived P2SH address %s does not match given address %s", derivedP2shAddress, p2shAddress));
				System.exit(2);
			}

			// Some checks

			System.out.println("\nProcessing:");

			long medianBlockTime;
			try {
				medianBlockTime = bitcoiny.getMedianBlockTime();
			} catch (ForeignBlockchainException e) {
				System.err.println("Unable to determine median block time");
				System.exit(2);
				return;
			}
			System.out.println(String.format("Median block time: %s", LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneOffset.UTC)));

			long now = System.currentTimeMillis();

			if (now < medianBlockTime * 1000L) {
				System.err.println(String.format("Too soon (%s) to refund based on median block time %s", LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC), LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneOffset.UTC)));
				System.exit(2);
			}

			if (now < lockTime * 1000L) {
				System.err.println(String.format("Too soon (%s) to refund based on lockTime %s", LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC), LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC)));
				System.exit(2);
			}

			// Check P2SH is funded
			long p2shBalance;
			try {
				p2shBalance = bitcoiny.getConfirmedBalance(p2shAddress.toString());
			} catch (ForeignBlockchainException e) {
				System.err.println(String.format("Unable to check P2SH address %s balance", p2shAddress));
				System.exit(2);
				return;
			}
			System.out.println(String.format("P2SH address %s balance: %s", p2shAddress, bitcoiny.format(p2shBalance)));

			// Grab all P2SH funding transactions (just in case there are more than one)
			List<TransactionOutput> fundingOutputs;
			try {
				fundingOutputs = bitcoiny.getUnspentOutputs(p2shAddress.toString());
			} catch (ForeignBlockchainException e) {
				System.err.println(String.format("Can't find outputs for P2SH"));
				System.exit(2);
				return;
			}

			System.out.println(String.format("Found %d output%s for P2SH", fundingOutputs.size(), (fundingOutputs.size() != 1 ? "s" : "")));

			for (TransactionOutput fundingOutput : fundingOutputs)
				System.out.println(String.format("Output %s:%d amount %s", HashCode.fromBytes(fundingOutput.getParentTransactionHash().getBytes()), fundingOutput.getIndex(), bitcoiny.format(fundingOutput.getValue())));

			if (fundingOutputs.isEmpty()) {
				System.err.println(String.format("Can't refund spent/unfunded P2SH"));
				System.exit(2);
			}

			if (fundingOutputs.size() != 1) {
				System.err.println(String.format("Expecting only one unspent output for P2SH"));
				// No longer fatal
			}

			for (TransactionOutput fundingOutput : fundingOutputs)
				System.out.println(String.format("Using output %s:%d for redeem", HashCode.fromBytes(fundingOutput.getParentTransactionHash().getBytes()), fundingOutput.getIndex()));

			Coin refundAmount = Coin.valueOf(p2shBalance).subtract(p2shFee);
			System.out.println(String.format("Spending %s of output, with %s as mining fee", bitcoiny.format(refundAmount), bitcoiny.format(p2shFee)));

			Transaction refundTransaction = BitcoinyHTLC.buildRefundTransaction(bitcoiny.getNetworkParameters(), refundAmount, refundKey,
					fundingOutputs, redeemScriptBytes, lockTime, outputAddress.getHash());

			byte[] rawTransactionBytes = refundTransaction.bitcoinSerialize();

			System.out.println(String.format("\nRaw transaction bytes:\n%s\n", HashCode.fromBytes(rawTransactionBytes).toString()));

			try {
				bitcoiny.broadcastTransaction(refundTransaction);
			} catch (ForeignBlockchainException e) {
				System.err.println(String.format("Failed to broadcast transaction: %s", e.getMessage()));
				System.exit(1);
			}
		} catch (NumberFormatException e) {
			usage(String.format("Number format exception: %s", e.getMessage()));
		}
	}

}
