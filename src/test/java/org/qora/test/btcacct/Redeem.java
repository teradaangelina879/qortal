package org.qora.test.btcacct;

import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
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
 * Initiator sends trade private key to Responder.
 * Responder uses their public key + tx signature + trade pubkey + script as input to BTC P2SH address, releasing BTC amount to responder.
 * 
 * Qora nodes scan for P2SH output, checks amount and recipient and if ok sends secret to Qora ACCT AT
 * (Or it's possible to feed BTC transaction details into Qora AT so it can check them itself?)
 * 
 * Qora ACCT AT sends its Qora to initiator
 *
 */

public class Redeem {

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	private static final long REFUND_TIMEOUT = 600L; // seconds

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: Redeem <your-BTC-pubkey> <their-BTC-P2PKH> <trade-PRIVATE-key> <locktime> <P2SH-address> (<BTC-redeem/refund-fee>)"));
		System.err.println(String.format("example: Redeem 032783606be32a3e639a33afe2b15f058708ab124f3b290d595ee954390a0c8559 \\\n"
				+ "\tmrTDPdM15cFWJC4g223BXX5snicfVJBx6M \\\n"
				+ "\teb95e1c1a5e9e6733549faec85b71f74f67638ea63b0acf2f077e9d0cb94dfe8 1575653814 2Mtn4aLjjWVEWckdoTMK7P8WbkXJf1ES6yL"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 5 || args.length > 6)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Settings.fileInstance("settings-test.json");
		NetworkParameters params = RegTestParams.get();
		// TestNet3Params.get();

		ECKey yourBitcoinKey = null;
		Address theirBitcoinAddress = null;
		byte[] tradePrivateKey = null;
		int lockTime = 0;
		Address p2shAddress = null;
		Coin bitcoinFee = BTCACCT.DEFAULT_BTC_FEE;

		try {
			int argIndex = 0;

			yourBitcoinKey = ECKey.fromPublicOnly(HashCode.fromString(args[argIndex++]).asBytes());

			theirBitcoinAddress = Address.fromString(params, args[argIndex++]);
			if (theirBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Their BTC address is not in P2PKH form");

			tradePrivateKey = HashCode.fromString(args[argIndex++]).asBytes();
			if (tradePrivateKey.length != 32)
				usage("Trade private key not 32 bytes");

			lockTime = Integer.parseInt(args[argIndex++]);

			p2shAddress = Address.fromString(params, args[argIndex++]);
			if (p2shAddress.getOutputScriptType() != ScriptType.P2SH)
				usage("P2SH address invalid");

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

			System.out.println(String.format("Your Bitcoin address: %s", Address.fromKey(params, yourBitcoinKey, ScriptType.P2PKH)));
			System.out.println(String.format("Their Bitcoin address: %s", theirBitcoinAddress));
			System.out.println(String.format("Trade PRIVATE key: %s", HashCode.fromBytes(tradePrivateKey)));
			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));
			System.out.println(String.format("P2SH address: %s", p2shAddress));
			System.out.println(String.format("Bitcoin redeem fee: %s", bitcoinFee.toPlainString()));

			// New/derived info

			System.out.println("\nCHECKING info from other party:");

			ECKey tradeKey = ECKey.fromPrivate(tradePrivateKey);
			System.out.println(String.format("Trade pubkeyhash: %s", HashCode.fromBytes(tradeKey.getPubKeyHash())));

			byte[] redeemScriptBytes = BTCACCT.buildScript(tradeKey.getPubKeyHash(), theirBitcoinAddress.getHash(), yourBitcoinKey.getPubKeyHash(), lockTime);
			System.out.println(String.format("Redeem script: %s", HashCode.fromBytes(redeemScriptBytes)));

			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);
			Address derivedP2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			if (!derivedP2shAddress.equals(p2shAddress)) {
				System.err.println(String.format("Derived P2SH address %s does not match given address %s", derivedP2shAddress, p2shAddress));
				System.exit(2);
			}

			// Some checks

			System.out.println("\nProcessing:");

			long medianBlockTime = BTC.getInstance().getMedianBlockTime();
			System.out.println(String.format("Median block time: %s", LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneOffset.UTC)));

			long now = System.currentTimeMillis();

			if (now < medianBlockTime * 1000L) {
				System.err.println(String.format("Too soon (%s) to redeem based on median block time %s", LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC), LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneOffset.UTC)));
				System.exit(2);
			}

			Coin p2shBalance = BTC.getInstance().getBalance(p2shAddress.toString(), lockTime - REFUND_TIMEOUT);
			if (p2shBalance == null) {
				System.err.println(String.format("Unable to check P2SH address %s balance", p2shAddress));
				System.exit(2);
			}
			System.out.println(String.format("P2SH address %s balance: %s BTC", p2shAddress, p2shBalance.toPlainString()));

			// Grab all P2SH funding transactions (just in case there are more than one)
			List<TransactionOutput> fundingOutputs = BTC.getInstance().getOutputs(p2shAddress.toString(), lockTime - REFUND_TIMEOUT);
			System.out.println(String.format("Found %d unspent output%s for P2SH", fundingOutputs.size(), (fundingOutputs.size() != 1 ? "s" : "")));

			if (fundingOutputs.isEmpty()) {
				System.err.println(String.format("Can't redeem spent/unfunded P2SH"));
				System.exit(2);
			}

			if (fundingOutputs.size() != 1) {
				System.err.println(String.format("Expecting only one unspent output for P2SH"));
				System.exit(2);
			}

			TransactionOutput fundingOutput = fundingOutputs.get(0);
			System.out.println(String.format("Using output %s:%d for redeem", HashCode.fromBytes(fundingOutput.getParentTransactionHash().getBytes()), fundingOutput.getIndex()));

			Coin redeemAmount = p2shBalance.subtract(bitcoinFee);
			Transaction redeemTransaction = BTCACCT.buildRedeemTransaction(redeemAmount, tradeKey, yourBitcoinKey.getPubKey(), fundingOutput, redeemScriptBytes);

			byte[] redeemBytes = redeemTransaction.bitcoinSerialize();

			System.out.println(String.format("\nLoad this transaction into your wallet and broadcast:\n%s\n", HashCode.fromBytes(redeemBytes).toString()));
		} catch (NumberFormatException e) {
			usage(String.format("Number format exception: %s", e.getMessage()));
		} catch (DataException e) {
			throw new RuntimeException("Repository issue: " + e.getMessage());
		}
	}

}
