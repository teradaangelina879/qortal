package org.qortal.test.crosschain.bitcoinv1;

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
import org.qortal.crosschain.Bitcoin;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.settings.Settings;

import com.google.common.hash.HashCode;

public class BuildHTLC {

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: BuildHTLC <refund-P2PKH> <BTC-amount> <redeem-P2PKH> <HASH160-of-secret> <locktime>"));
		System.err.println(String.format("example: BuildHTLC "
				+ "mrTDPdM15cFWJC4g223BXX5snicfVJBx6M \\\n"
				+ "\t0.00008642 \\\n"
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

		Address refundBitcoinAddress = null;
		Coin bitcoinAmount = null;
		Address redeemBitcoinAddress = null;
		byte[] secretHash = null;
		int lockTime = 0;

		int argIndex = 0;
		try {
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
			if (refundTimeoutDelay < 600 || refundTimeoutDelay > 30 * 24 * 60 * 60)
				usage("Locktime (seconds) should be at between 10 minutes and 1 month from now");
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		Coin p2shFee;
		try {
			p2shFee = Coin.valueOf(bitcoin.getP2shFee(null));
		} catch (ForeignBlockchainException e) {
			throw new RuntimeException(e.getMessage());
		}

		System.out.println("Confirm the following is correct based on the info you've given:");

		System.out.println(String.format("Refund Bitcoin address: %s", refundBitcoinAddress));
		System.out.println(String.format("Bitcoin redeem amount: %s", bitcoinAmount.toPlainString()));

		System.out.println(String.format("Redeem Bitcoin address: %s", redeemBitcoinAddress));
		System.out.println(String.format("Redeem miner's fee: %s", bitcoin.format(p2shFee)));

		System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));
		System.out.println(String.format("Hash of secret: %s", HashCode.fromBytes(secretHash)));

		byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(refundBitcoinAddress.getHash(), lockTime, redeemBitcoinAddress.getHash(), secretHash);
		System.out.println(String.format("Redeem script: %s", HashCode.fromBytes(redeemScriptBytes)));

		String p2shAddress = bitcoin.deriveP2shAddress(redeemScriptBytes);
		System.out.println(String.format("P2SH address: %s", p2shAddress));

		bitcoinAmount = bitcoinAmount.add(p2shFee);

		// Fund P2SH
		System.out.println(String.format("\nYou need to fund %s with %s (includes redeem/refund fee of %s)",
				p2shAddress, bitcoin.format(bitcoinAmount), bitcoin.format(p2shFee)));

		System.out.println("Once this is done, responder should run Respond to check P2SH funding and create AT");
	}

}
