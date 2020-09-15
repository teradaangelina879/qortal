package org.qortal.test.crosschain.bitcoinv1;

import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
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

public class CheckHTLC {

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: CheckHTLC <P2SH-address> <refund-P2PKH> <BTC-amount> <redeem-P2PKH> <HASH160-of-secret> <locktime>"));
		System.err.println(String.format("example: CheckP2SH "
				+ "2NEZboTLhBDPPQciR7sExBhy3TsDi7wV3Cv \\\n"
				+ "mrTDPdM15cFWJC4g223BXX5snicfVJBx6M \\\n"
				+ "\t0.00008642 \\\n"
				+ "\tn2N5VKrzq39nmuefZwp3wBiF4icdXX2B6o \\\n"
				+ "\tdaf59884b4d1aec8c1b17102530909ee43c0151a \\\n"
				+ "\t1585920000"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 6 || args.length > 6)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		Settings.fileInstance("settings-test.json");

		Bitcoin bitcoin = Bitcoin.getInstance();
		NetworkParameters params = bitcoin.getNetworkParameters();

		Address p2shAddress = null;
		Address refundBitcoinAddress = null;
		Coin bitcoinAmount = null;
		Address redeemBitcoinAddress = null;
		byte[] secretHash = null;
		int lockTime = 0;

		int argIndex = 0;
		try {
			p2shAddress = Address.fromString(params, args[argIndex++]);
			if (p2shAddress.getOutputScriptType() != ScriptType.P2SH)
				usage("P2SH address invalid");

			refundBitcoinAddress = Address.fromString(params, args[argIndex++]);
			if (refundBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Refund BTC address must be in P2PKH form");

			bitcoinAmount = Coin.parseCoin(args[argIndex++]);

			redeemBitcoinAddress = Address.fromString(params, args[argIndex++]);
			if (redeemBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Redeem BTC address must be in P2PKH form");

			secretHash = HashCode.fromString(args[argIndex++]).asBytes();
			if (secretHash.length != 20)
				usage("Hash of secret must be 20 bytes");

			lockTime = Integer.parseInt(args[argIndex++]);
			int refundTimeoutDelay = lockTime - (int) (System.currentTimeMillis() / 1000L);
			if (refundTimeoutDelay < 600 || refundTimeoutDelay > 7 * 24 * 60 * 60)
				usage("Locktime (seconds) should be at between 10 minutes and 1 week from now");
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

			System.out.println(String.format("Refund Bitcoin address: %s", redeemBitcoinAddress));
			System.out.println(String.format("Bitcoin redeem amount: %s", bitcoinAmount.toPlainString()));

			System.out.println(String.format("Redeem Bitcoin address: %s", refundBitcoinAddress));
			System.out.println(String.format("Redeem miner's fee: %s", bitcoin.format(p2shFee)));

			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));
			System.out.println(String.format("Hash of secret: %s", HashCode.fromBytes(secretHash)));

			System.out.println(String.format("P2SH address: %s", p2shAddress));

			byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(refundBitcoinAddress.getHash(), lockTime, redeemBitcoinAddress.getHash(), secretHash);
			System.out.println(String.format("Redeem script: %s", HashCode.fromBytes(redeemScriptBytes)));

			byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
			Address derivedP2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			if (!derivedP2shAddress.equals(p2shAddress)) {
				System.err.println(String.format("Derived P2SH address %s does not match given address %s", derivedP2shAddress, p2shAddress));
				System.exit(2);
			}

			bitcoinAmount = bitcoinAmount.add(p2shFee);

			long medianBlockTime = bitcoin.getMedianBlockTime();
			System.out.println(String.format("Median block time: %s", LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneOffset.UTC)));

			long now = System.currentTimeMillis();

			if (now < medianBlockTime * 1000L)
				System.out.println(String.format("Too soon (%s) to redeem based on median block time %s", LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC), LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneOffset.UTC)));

			// Check P2SH is funded
			long p2shBalance = bitcoin.getConfirmedBalance(p2shAddress.toString());
			System.out.println(String.format("P2SH address %s balance: %s", p2shAddress, bitcoin.format(p2shBalance)));

			// Grab all P2SH funding transactions (just in case there are more than one)
			List<TransactionOutput> fundingOutputs = bitcoin.getUnspentOutputs(p2shAddress.toString());
			if (fundingOutputs == null) {
				System.err.println(String.format("Can't find outputs for P2SH"));
				System.exit(2);
			}

			System.out.println(String.format("Found %d output%s for P2SH", fundingOutputs.size(), (fundingOutputs.size() != 1 ? "s" : "")));

			for (TransactionOutput fundingOutput : fundingOutputs)
				System.out.println(String.format("Output %s:%d amount %s", HashCode.fromBytes(fundingOutput.getParentTransactionHash().getBytes()), fundingOutput.getIndex(), bitcoin.format(fundingOutput.getValue())));

			if (fundingOutputs.isEmpty()) {
				System.err.println(String.format("Can't redeem spent/unfunded P2SH"));
				System.exit(2);
			}

			if (fundingOutputs.size() != 1) {
				System.err.println(String.format("Expecting only one unspent output for P2SH"));
				System.exit(2);
			}
		} catch (ForeignBlockchainException e) {
			System.err.println("Bitcoin issue: " + e.getMessage());
		}
	}

}
