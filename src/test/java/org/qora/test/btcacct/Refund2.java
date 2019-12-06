package org.qora.test.btcacct;

import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
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

public class Refund2 {

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	private static final long REFUND_TIMEOUT = 600L; // seconds

	private static void usage() {
		System.err.println(String.format("usage: Refund2 <your-BTC-PRIVkey> <their-BTC-pubkey> <hash-of-secret> <locktime> <P2SH-address>"));
		System.err.println(String.format("example: Refund2 027fb5828c5e201eaf6de4cd3b0b340d16a191ef848cd691f35ef8f727358c9c \\\n"
				+ "\t032783606be32a3e639a33afe2b15f058708ab124f3b290d595ee954390a0c8559 \\\n"
				+ "\tb837056cdc5d805e4db1f830a58158e1131ac96ea71de4c6f9d7854985e153e2 1575021641 2MvGdGUgAfc7qTHaZJwWmZ26Fg6Hjif8gNy"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length != 5)
			usage();

		Security.insertProviderAt(new BouncyCastleProvider(), 0);

		Settings.fileInstance("settings-test.json");

		NetworkParameters params = TestNet3Params.get();

		int argIndex = 0;
		String yourBitcoinPrivKeyHex = args[argIndex++];
		String theirBitcoinPubKeyHex = args[argIndex++];

		String secretHashHex = args[argIndex++];
		String rawLockTime = args[argIndex++];
		String rawP2shAddress = args[argIndex++];

		try {
			RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(Controller.getRepositoryUrl());
			RepositoryManager.setRepositoryFactory(repositoryFactory);
		} catch (DataException e) {
			throw new RuntimeException("Repository startup issue: " + e.getMessage());
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			System.out.println("Confirm the following is correct based on the info you've given:");

			byte[] yourBitcoinPrivKey = HashCode.fromString(yourBitcoinPrivKeyHex).asBytes();
			ECKey yourBitcoinKey = ECKey.fromPrivate(yourBitcoinPrivKey);
			Address yourBitcoinAddress = Address.fromKey(params, yourBitcoinKey, ScriptType.P2PKH);
			System.out.println(String.format("Your Bitcoin address: %s", yourBitcoinAddress));

			byte[] theirBitcoinPubKey = HashCode.fromString(theirBitcoinPubKeyHex).asBytes();
			ECKey theirBitcoinKey = ECKey.fromPublicOnly(theirBitcoinPubKey);
			Address theirBitcoinAddress = Address.fromKey(params, theirBitcoinKey, ScriptType.P2PKH);
			System.out.println(String.format("Their Bitcoin address: %s", theirBitcoinAddress));

			// New/derived info

			int lockTime = Integer.valueOf(rawLockTime);
			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneId.systemDefault()), lockTime));

			byte[] secretHash = HashCode.fromString(secretHashHex).asBytes();
			System.out.println("Hash of secret: " + HashCode.fromBytes(secretHash).toString());

			byte[] redeemScriptBytes = BTCACCT.buildRedeemScript(secretHash, yourBitcoinKey.getPubKey(), theirBitcoinPubKey, lockTime);
			System.out.println("Redeem script: " + HashCode.fromBytes(redeemScriptBytes).toString());

			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
			System.out.println(String.format("P2SH address: %s", p2shAddress));

			if (!p2shAddress.toString().equals(rawP2shAddress)) {
				System.err.println(String.format("Derived P2SH address %s does not match given address %s", p2shAddress, rawP2shAddress));
				System.exit(2);
			}

			// Some checks
			long medianBlockTime = BTC.getInstance().getMedianBlockTime();
			System.out.println(String.format("Median block time: %s", LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneId.systemDefault())));

			long now = System.currentTimeMillis();

			if (now < medianBlockTime * 1000L) {
				System.err.println(String.format("Too soon (%s) to refund based on median block time %s", LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()), LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneId.systemDefault())));
				System.exit(2);
			}

			if (now < lockTime * 1000L) {
				System.err.println(String.format("Too soon (%s) to refund based on lockTime %s", LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()), LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneId.systemDefault())));
				System.exit(2);
			}

			Coin p2shBalance = BTC.getInstance().getBalance(p2shAddress.toString(), lockTime - REFUND_TIMEOUT);
			if (p2shBalance == null) {
				System.err.println(String.format("Unable to check P2SH address %s balance", p2shAddress));
				System.exit(2);
			}
			System.out.println(String.format("P2SH address %s balance: %s BTC", p2shAddress, p2shBalance.toPlainString()));

			// Grab all P2SH funding transactions (just in case there are more than one)
			List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress.toString(), lockTime - REFUND_TIMEOUT);
			System.out.println(String.format("Found %d unspent output%s for P2SH", fundingOutputs.size(), (fundingOutputs.size() != 1 ? "s" : "")));

			if (fundingOutputs.isEmpty()) {
				System.err.println(String.format("Can't refund spent/unfunded P2SH"));
				System.exit(2);
			}

			if (fundingOutputs.size() != 1) {
				System.err.println(String.format("Expecting only one unspent output for P2SH"));
				System.exit(2);
			}

			Transaction refundTransaction = BTCACCT.buildRefundTransaction(p2shBalance, yourBitcoinKey, fundingOutputs.get(0), redeemScriptBytes, lockTime);

			byte[] refundBytes = refundTransaction.bitcoinSerialize();

			System.out.println(String.format("\nLoad this transaction into your wallet, sign and broadcast:\n%s\n", HashCode.fromBytes(refundBytes).toString()));
		} catch (NumberFormatException e) {
			usage();
		} catch (DataException e) {
			throw new RuntimeException("Repository issue: " + e.getMessage());
		}
	}

}
