package org.qortal.test.crosschain.litecoinv1;

import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.Script.ScriptType;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.Litecoin;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.settings.Settings;

import com.google.common.hash.HashCode;

public class BuildHTLC {

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: BuildHTLC <refund-P2PKH> <LTC-amount> <redeem-P2PKH> <HASH160-of-secret> <locktime>"));
		System.err.println(String.format("example: BuildHTLC "
				+ "msAfaDaJ8JiprxxFaAXEEPxKK3JaZCYpLv \\\n"
				+ "\t0.00008642 \\\n"
				+ "\tmrBpZYYGYMwUa8tRjTiXfP1ySqNXszWN5h \\\n"
				+ "\tdaf59884b4d1aec8c1b17102530909ee43c0151a \\\n"
				+ "\t1600000000"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 5 || args.length > 5)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		Settings.fileInstance("settings-test.json");

		Litecoin litecoin = Litecoin.getInstance();
		NetworkParameters params = litecoin.getNetworkParameters();

		Address refundLitecoinAddress = null;
		Coin litecoinAmount = null;
		Address redeemLitecoinAddress = null;
		byte[] secretHash = null;
		int lockTime = 0;

		int argIndex = 0;
		try {
			refundLitecoinAddress = Address.fromString(params, args[argIndex++]);
			if (refundLitecoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Refund Litecoin address must be in P2PKH form");

			litecoinAmount = Coin.parseCoin(args[argIndex++]);

			redeemLitecoinAddress = Address.fromString(params, args[argIndex++]);
			if (redeemLitecoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Redeem Litecoin address must be in P2PKH form");

			secretHash = HashCode.fromString(args[argIndex++]).asBytes();
			if (secretHash.length != 20)
				usage("Hash of secret must be 20 bytes");

			lockTime = Integer.parseInt(args[argIndex++]);
			int refundTimeoutDelay = lockTime - (int) (System.currentTimeMillis() / 1000L);
			if (refundTimeoutDelay < 600 || refundTimeoutDelay > 30 * 24 * 60 * 60)
				usage("Locktime (seconds) should be at between 10 minutes and 1 month from now");
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		Coin p2shFee;
		try {
			p2shFee = Coin.valueOf(litecoin.getP2shFee(null));
		} catch (ForeignBlockchainException e) {
			throw new RuntimeException(e.getMessage());
		}

		System.out.println("Confirm the following is correct based on the info you've given:");

		System.out.println(String.format("Refund Litecoin address: %s", refundLitecoinAddress));
		System.out.println(String.format("Litecoin redeem amount: %s", litecoinAmount.toPlainString()));

		System.out.println(String.format("Redeem Litecoin address: %s", redeemLitecoinAddress));
		System.out.println(String.format("Redeem miner's fee: %s", litecoin.format(p2shFee)));

		System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));
		System.out.println(String.format("Hash of secret: %s", HashCode.fromBytes(secretHash)));

		byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(refundLitecoinAddress.getHash(), lockTime, redeemLitecoinAddress.getHash(), secretHash);
		System.out.println(String.format("Redeem script: %s", HashCode.fromBytes(redeemScriptBytes)));

		String p2shAddress = litecoin.deriveP2shAddress(redeemScriptBytes);
		System.out.println(String.format("P2SH address: %s", p2shAddress));

		litecoinAmount = litecoinAmount.add(p2shFee);

		// Fund P2SH
		System.out.println(String.format("\nYou need to fund %s with %s (includes redeem/refund fee of %s)",
				p2shAddress, litecoin.format(litecoinAmount), litecoin.format(p2shFee)));

		System.out.println("Once this is done, responder should run Respond to check P2SH funding and create AT");
	}

}
