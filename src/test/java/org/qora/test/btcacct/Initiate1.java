package org.qora.test.btcacct;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script.ScriptType;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.qora.account.PrivateKeyAccount;
import org.qora.account.PublicKeyAccount;
import org.qora.asset.Asset;
import org.qora.controller.Controller;
import org.qora.crosschain.BTC;
import org.qora.crosschain.BTCACCT;
import org.qora.crypto.Crypto;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryFactory;
import org.qora.repository.RepositoryManager;
import org.qora.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qora.utils.Base58;

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

public class Initiate1 {

	private static final long REFUND_TIMEOUT = 600L; // seconds

	private static void usage() {
		System.err.println(String.format("usage: Initiate1 <your-QORT-PRIVkey> <your-BTC-pubkey> <QORT-amount> <BTC-amount> <their-QORT-pubkey> <their-BTC-pubkey>"));
		System.err.println(String.format("example: Initiate1 pYQ6DpQBJ2n72TCLJLScEvwhf3boxWy2kQEPynakwpj \\\n"
				+ "\t03aa20871c2195361f2826c7a649eab6b42639630c4d8c33c55311d5c1e476b5d6 \\\n"
				+ "\t123 0.00008642 \\\n"
				+ "\tJBNBQQDzZsm5do1BrwWAp53Ps4KYJVt749EGpCf7ofte \\\n"
				+ "\t032783606be32a3e639a33afe2b15f058708ab124f3b290d595ee954390a0c8559"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length != 6)
			usage();

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		NetworkParameters params = TestNet3Params.get();

		int argIndex = 0;
		String yourQortPrivKey58 = args[argIndex++];
		String yourBitcoinPubKeyHex = args[argIndex++];

		String rawQortAmount = args[argIndex++];
		String rawBitcoinAmount = args[argIndex++];

		String theirQortPubKey58 = args[argIndex++];
		String theirBitcoinPubKeyHex = args[argIndex++];

		try {
			RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(Controller.getRepositoryUrl());
			RepositoryManager.setRepositoryFactory(repositoryFactory);
		} catch (DataException e) {
			throw new RuntimeException("Repository startup issue: " + e.getMessage());
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			System.out.println("Confirm the following is correct based on the info you've given:");

			byte[] yourQortPrivKey = Base58.decode(yourQortPrivKey58);
			PrivateKeyAccount yourQortalAccount = new PrivateKeyAccount(repository, yourQortPrivKey);
			System.out.println(String.format("Your Qortal address: %s", yourQortalAccount.getAddress()));

			byte[] yourBitcoinPubKey = HashCode.fromString(yourBitcoinPubKeyHex).asBytes();
			ECKey yourBitcoinKey = ECKey.fromPublicOnly(yourBitcoinPubKey);
			Address yourBitcoinAddress = Address.fromKey(params, yourBitcoinKey, ScriptType.P2PKH);
			System.out.println(String.format("Your Bitcoin address: %s", yourBitcoinAddress));

			byte[] theirQortPubKey = Base58.decode(theirQortPubKey58);
			PublicKeyAccount theirQortalAccount = new PublicKeyAccount(repository, theirQortPubKey);
			System.out.println(String.format("Their Qortal address: %s", theirQortalAccount.getAddress()));

			byte[] theirBitcoinPubKey = HashCode.fromString(theirBitcoinPubKeyHex).asBytes();
			ECKey theirBitcoinKey = ECKey.fromPublicOnly(theirBitcoinPubKey);
			Address theirBitcoinAddress = Address.fromKey(params, theirBitcoinKey, ScriptType.P2PKH);
			System.out.println(String.format("Their Bitcoin address: %s", theirBitcoinAddress));

			// Some checks
			BigDecimal qortAmount = new BigDecimal(rawQortAmount).setScale(8);
			BigDecimal yourQortBalance = yourQortalAccount.getConfirmedBalance(Asset.QORT);
			if (yourQortBalance.compareTo(qortAmount) <= 0) {
				System.err.println(String.format("Your QORT balance %s is less than required %s", yourQortBalance.toPlainString(), qortAmount.toPlainString()));
				System.exit(2);
			}

			// New/derived info

			byte[] secret = new byte[32];
			new SecureRandom().nextBytes(secret);
			System.out.println("\nSecret info (DO NOT share with other party):");
			System.out.println("Secret: " + HashCode.fromBytes(secret).toString());

			System.out.println("\nGive this info to other party:");

			byte[] secretHash = Crypto.digest(secret);
			System.out.println("Hash of secret: " + HashCode.fromBytes(secretHash).toString());

			int lockTime = (int) ((System.currentTimeMillis() / 1000L) + REFUND_TIMEOUT);
			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneId.systemDefault()), lockTime));

			byte[] redeemScriptBytes = BTCACCT.buildRedeemScript(secretHash, yourBitcoinPubKey, theirBitcoinPubKey, lockTime);
			System.out.println("Redeem script: " + HashCode.fromBytes(redeemScriptBytes).toString());

			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
			System.out.println("P2SH address: " + p2shAddress.toString());

			Coin bitcoinAmount = Coin.parseCoin(rawBitcoinAmount).add(BTCACCT.DEFAULT_BTC_FEE);

			// Fund P2SH
			System.out.println(String.format("\nYou need to fund %s with %s BTC (includes redeem/refund fee)", p2shAddress.toString(), bitcoinAmount.toPlainString()));

			System.out.println("Once this is done, responder should run Respond2 to check P2SH funding and create AT");
		} catch (NumberFormatException e) {
			usage();
		} catch (DataException e) {
			throw new RuntimeException("Repository issue: " + e.getMessage());
		}
	}

}
