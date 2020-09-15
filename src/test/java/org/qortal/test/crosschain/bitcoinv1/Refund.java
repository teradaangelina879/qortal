package org.qortal.test.crosschain.bitcoinv1;

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
import org.qortal.crosschain.Bitcoin;
import org.qortal.crosschain.ForeignBlockchainException;
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

		System.err.println(String.format("usage: Refund <P2SH-address> <refund-PRIVATE-KEY> <redeem-P2PKH> <HASH160-of-secret> <locktime>"));
		System.err.println(String.format("example: Refund "
				+ "2NEZboTLhBDPPQciR7sExBhy3TsDi7wV3Cv \\\n"
				+ "\tef027fb5828c5e201eaf6de4cd3b0b340d16a191ef848cd691f35ef8f727358c9c01b576fb7e \\\n"
				+ "\tn2N5VKrzq39nmuefZwp3wBiF4icdXX2B6o \\\n"
				+ "\tdaf59884b4d1aec8c1b17102530909ee43c0151a \\\n"
				+ "\t1585920000"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 5 || args.length > 5)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		Settings.fileInstance("settings-test.json");

		Bitcoin bitcoin = Bitcoin.getInstance();
		NetworkParameters params = bitcoin.getNetworkParameters();

		Address p2shAddress = null;
		byte[] refundPrivateKey = null;
		Address redeemBitcoinAddress = null;
		byte[] secretHash = null;
		int lockTime = 0;

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

			redeemBitcoinAddress = Address.fromString(params, args[argIndex++]);
			if (redeemBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Their BTC address must be in P2PKH form");

			secretHash = HashCode.fromString(args[argIndex++]).asBytes();
			if (secretHash.length != 20)
				usage("HASH160 of secret must be 20 bytes");

			lockTime = Integer.parseInt(args[argIndex++]);
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		Coin p2shFee;
		try {
			p2shFee = Coin.valueOf(bitcoin.getP2shFee(null));
		} catch (ForeignBlockchainException e) {
			throw new RuntimeException(e.getMessage());
		}

		try {
			System.out.println("Confirm the following is correct based on the info you've given:");

			System.out.println(String.format("Refund PRIVATE key: %s", HashCode.fromBytes(refundPrivateKey)));
			System.out.println(String.format("Redeem Bitcoin address: %s", redeemBitcoinAddress));
			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));
			System.out.println(String.format("P2SH address: %s", p2shAddress));
			System.out.println(String.format("Refund miner's fee: %s", bitcoin.format(p2shFee)));

			// New/derived info

			System.out.println("\nCHECKING info from other party:");

			ECKey refundKey = ECKey.fromPrivate(refundPrivateKey);
			Address refundAddress = Address.fromKey(params, refundKey, ScriptType.P2PKH);
			System.out.println(String.format("Refund recipient (PKH): %s (%s)", refundAddress, HashCode.fromBytes(refundAddress.getHash())));

			byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(refundAddress.getHash(), lockTime, redeemBitcoinAddress.getHash(), secretHash);
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
				medianBlockTime = bitcoin.getMedianBlockTime();
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
				p2shBalance = bitcoin.getConfirmedBalance(p2shAddress.toString());
			} catch (ForeignBlockchainException e) {
				System.err.println(String.format("Unable to check P2SH address %s balance", p2shAddress));
				System.exit(2);
				return;
			}
			System.out.println(String.format("P2SH address %s balance: %s", p2shAddress, bitcoin.format(p2shBalance)));

			// Grab all P2SH funding transactions (just in case there are more than one)
			List<TransactionOutput> fundingOutputs;
			try {
				fundingOutputs = bitcoin.getUnspentOutputs(p2shAddress.toString());
			} catch (ForeignBlockchainException e) {
				System.err.println(String.format("Can't find outputs for P2SH"));
				System.exit(2);
				return;
			}

			System.out.println(String.format("Found %d output%s for P2SH", fundingOutputs.size(), (fundingOutputs.size() != 1 ? "s" : "")));

			for (TransactionOutput fundingOutput : fundingOutputs)
				System.out.println(String.format("Output %s:%d amount %s", HashCode.fromBytes(fundingOutput.getParentTransactionHash().getBytes()), fundingOutput.getIndex(), bitcoin.format(fundingOutput.getValue())));

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
			System.out.println(String.format("Spending %s of output, with %s as mining fee", bitcoin.format(refundAmount), bitcoin.format(p2shFee)));

			Transaction redeemTransaction = BitcoinyHTLC.buildRefundTransaction(bitcoin.getNetworkParameters(), refundAmount, refundKey,
					fundingOutputs, redeemScriptBytes, lockTime, refundKey.getPubKeyHash());

			byte[] redeemBytes = redeemTransaction.bitcoinSerialize();

			System.out.println(String.format("\nLoad this transaction into your wallet and broadcast:\n%s\n", HashCode.fromBytes(redeemBytes).toString()));
		} catch (NumberFormatException e) {
			usage(String.format("Number format exception: %s", e.getMessage()));
		}
	}

}
