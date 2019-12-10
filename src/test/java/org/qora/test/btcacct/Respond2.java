package org.qora.test.btcacct;

import java.math.BigDecimal;
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
import org.qora.data.transaction.BaseTransactionData;
import org.qora.data.transaction.DeployAtTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.group.Group;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryFactory;
import org.qora.repository.RepositoryManager;
import org.qora.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qora.settings.Settings;
import org.qora.transaction.DeployAtTransaction;
import org.qora.transaction.Transaction;
import org.qora.transform.TransformationException;
import org.qora.transform.transaction.TransactionTransformer;
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

public class Respond2 {

	private static final long REFUND_TIMEOUT = 600L; // seconds

	private static void usage() {
		System.err.println(String.format("usage: Respond2 <your-BTC-pubkey> <BTC-amount> <their-BTC-pubkey> <trade-pubkeyhash> <locktime> <P2SH-address>"));
		System.err.println(String.format("example: Respond2 3jjoToDaDpsdUHqaouLGypFeewNVKvtkmdM38i54WVra \\\n"
				+ "\t032783606be32a3e639a33afe2b15f058708ab124f3b290d595ee954390a0c8559 \\\n"
				+ "\t123 0.00008642 \\\n"
				+ "\t6rNn9b3pYRrG9UKqzMWYZ9qa8F3Zgv2mVWrULGHUusb \\\n"
				+ "\t03aa20871c2195361f2826c7a649eab6b42639630c4d8c33c55311d5c1e476b5d6 \\\n"
				+ "\tb837056cdc5d805e4db1f830a58158e1131ac96ea71de4c6f9d7854985e153e2 1575021641 2MvGdGUgAfc7qTHaZJwWmZ26Fg6Hjif8gNy"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length != 9)
			usage();

		Security.insertProviderAt(new BouncyCastleProvider(), 0);

		Settings.fileInstance("settings-test.json");

		NetworkParameters params = TestNet3Params.get();

		int argIndex = 0;
		String yourQortPrivKey58 = args[argIndex++];
		String yourBitcoinPubKeyHex = args[argIndex++];

		String rawQortAmount = args[argIndex++];
		String rawBitcoinAmount = args[argIndex++];

		String theirQortPubKey58 = args[argIndex++];
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

			byte[] yourQortPrivKey = Base58.decode(yourQortPrivKey58);
			PrivateKeyAccount yourQortalAccount = new PrivateKeyAccount(repository, yourQortPrivKey);
			byte[] yourQortPubKey = yourQortalAccount.getPublicKey();
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

			System.out.println("Hash of secret: " + secretHashHex);

			// New/derived info

			System.out.println("\nCHECKING info from other party:");

			int lockTime = Integer.valueOf(rawLockTime);
			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneId.systemDefault()), lockTime));

			byte[] secretHash = HashCode.fromString(secretHashHex).asBytes();
			System.out.println("Hash of secret: " + HashCode.fromBytes(secretHash).toString());

			byte[] redeemScriptBytes = BTCACCT.buildScript(secretHash, theirBitcoinPubKey, yourBitcoinPubKey, lockTime);
			System.out.println("Redeem script: " + HashCode.fromBytes(redeemScriptBytes).toString());

			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
			System.out.println(String.format("P2SH address: %s", p2shAddress));

			if (!p2shAddress.toString().equals(rawP2shAddress)) {
				System.err.println(String.format("Derived P2SH address %s does not match given address %s", p2shAddress, rawP2shAddress));
				System.exit(2);
			}

			// Check for funded P2SH
			Coin bitcoinAmount = Coin.parseCoin(rawBitcoinAmount).add(BTCACCT.DEFAULT_BTC_FEE);

			Coin p2shBalance = BTC.getInstance().getBalance(p2shAddress.toString(), lockTime - REFUND_TIMEOUT);
			if (p2shBalance == null) {
				System.err.println(String.format("Unable to check P2SH address %s balance", p2shAddress));
				System.exit(2);
			}
			if (p2shBalance.isLessThan(bitcoinAmount)) {
				System.err.println(String.format("P2SH address %s has lower balance than expected %s BTC", p2shAddress, p2shBalance.toPlainString()));
				System.exit(2);
			}
			System.out.println(String.format("P2SH address %s balance: %s BTC", p2shAddress, p2shBalance.toPlainString()));

			System.out.println("\nYour response:");

			// If good, deploy AT
			byte[] creationBytes = BTCACCT.buildCiyamAT(secretHash, theirQortPubKey, REFUND_TIMEOUT / 60);
			System.out.println("CIYAM AT creation bytes: " + HashCode.fromBytes(creationBytes).toString());

			BigDecimal qortAmount = new BigDecimal(rawQortAmount).setScale(8);

			long txTimestamp = System.currentTimeMillis();
			byte[] lastReference = yourQortalAccount.getLastReference();

			if (lastReference == null) {
				System.err.println(String.format("Qortal account %s has no last reference", yourQortalAccount.getAddress()));
				System.exit(2);
			}

			BigDecimal fee = BigDecimal.ZERO;
			BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, yourQortPubKey, fee, null);
			TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, "QORT-BTC", "QORT-BTC ACCT", "", "", creationBytes, qortAmount, Asset.QORT);

			Transaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);

			fee = deployAtTransaction.calcRecommendedFee();
			deployAtTransactionData.setFee(fee);

			deployAtTransaction.sign(yourQortalAccount);

			byte[] signedBytes = null;
			try {
				signedBytes = TransactionTransformer.toBytes(deployAtTransactionData);
			} catch (TransformationException e) {
				System.err.println(String.format("Unable to convert transaction to base58: %s", e.getMessage()));
				System.exit(2);
			}

			System.out.println(String.format("\nSigned transaction in base58, ready for POST /transactions/process:\n%s\n", Base58.encode(signedBytes)));
		} catch (NumberFormatException e) {
			usage();
		} catch (DataException e) {
			throw new RuntimeException("Repository issue: " + e.getMessage());
		}
	}

}
