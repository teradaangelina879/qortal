package org.qora.test.btcacct;

import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script.ScriptType;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.qora.controller.Controller;
import org.qora.crosschain.BTC;
import org.qora.crosschain.BTCACCT;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryFactory;
import org.qora.repository.RepositoryManager;
import org.qora.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qora.settings.Settings;

import com.google.common.hash.HashCode;

/**
 * Initiator must be Qora-chain so that initiator can send initial message to BTC P2SH then Qora can scan for P2SH add send corresponding message to Qora AT.
 *
 * Initiator (wants Qora, has BTC)
 * 		Funds BTC P2SH address
 * 
 * Responder (has Qora, wants BTC)
 * 		Builds Qora ACCT AT and funds it with Qora
 * 
 * Initiator sends recipient+secret+script as input to BTC P2SH address, releasing BTC amount - fees to responder
 * 
 * Qora nodes scan for P2SH output, checks amount and recipient and if ok sends secret to Qora ACCT AT
 * (Or it's possible to feed BTC transaction details into Qora AT so it can check them itself?)
 * 
 * Qora ACCT AT sends its Qora to initiator
 *
 */

public class Initiate {

	private static final long REFUND_TIMEOUT = 600L; // seconds

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: Initiate <your-BTC-P2PKH> <BTC-amount> <their-BTC-P2PKH> (<BTC-redeem/refund-fee>)"));
		System.err.println(String.format("example: Initiate mrTDPdM15cFWJC4g223BXX5snicfVJBx6M \\\n"
				+ "\t0.00008642 \\\n"
				+ "\tn2N5VKrzq39nmuefZwp3wBiF4icdXX2B6o"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 3 || args.length > 4)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Settings.fileInstance("settings-test.json");
		NetworkParameters params = RegTestParams.get();
		// TestNet3Params.get();

		Address yourBitcoinAddress = null;
		Coin bitcoinAmount = null;
		Address theirBitcoinAddress = null;
		Coin bitcoinFee = BTCACCT.DEFAULT_BTC_FEE;

		try {
			int argIndex = 0;

			yourBitcoinAddress = Address.fromString(params, args[argIndex++]);
			if (yourBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Your BTC address is not in P2PKH form");

			bitcoinAmount = Coin.parseCoin(args[argIndex++]);

			theirBitcoinAddress = Address.fromString(params, args[argIndex++]);
			if (theirBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Their BTC address is not in P2PKH form");

			if (args.length > argIndex)
				bitcoinFee = Coin.parseCoin(args[argIndex++]);
		} catch (NumberFormatException | AddressFormatException e) {
			usage(String.format("Argument format exception: %s", e.getMessage()));
		}

		try {
			RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(Controller.getRepositoryUrl());
			RepositoryManager.setRepositoryFactory(repositoryFactory);
		} catch (DataException e) {
			throw new RuntimeException("Repository startup issue: " + e.getMessage());
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			System.out.println("Confirm the following is correct based on the info you've given:");

			System.out.println(String.format("Your Bitcoin address: %s", yourBitcoinAddress));
			System.out.println(String.format("Their Bitcoin address: %s", theirBitcoinAddress));
			System.out.println(String.format("Bitcoin redeem amount: %s", bitcoinAmount.toPlainString()));
			System.out.println(String.format("Bitcoin redeem fee: %s", bitcoinFee.toPlainString()));

			// New/derived info

			ECKey tradeKey = new ECKey();
			System.out.println("\nSecret info (DO NOT share with other party):");
			System.out.println(String.format("Trade private key: %s", HashCode.fromBytes(tradeKey.getPrivKeyBytes())));

			System.out.println("\nGive this info to other party:");

			System.out.println(String.format("Trade pubkeyhash: %s", HashCode.fromBytes(tradeKey.getPubKeyHash())));

			int lockTime = (int) ((System.currentTimeMillis() / 1000L) + REFUND_TIMEOUT);
			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));

			byte[] redeemScriptBytes = BTCACCT.buildScript(tradeKey.getPubKeyHash(), yourBitcoinAddress.getHash(), theirBitcoinAddress.getHash(), lockTime);
			System.out.println(String.format("Redeem script: %s", HashCode.fromBytes(redeemScriptBytes)));

			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
			System.out.println(String.format("P2SH address: %s", p2shAddress));

			bitcoinAmount = bitcoinAmount.add(bitcoinFee);

			// Fund P2SH
			System.out.println(String.format("\nYou need to fund %s with %s BTC (includes redeem/refund fee of %s)",
					p2shAddress.toString(), bitcoinAmount.toPlainString(), bitcoinFee.toPlainString()));

			System.out.println("Once this is done, responder should run Respond to check P2SH funding and create AT");
		} catch (DataException e) {
			throw new RuntimeException("Repository issue: " + e.getMessage());
		}
	}

}
