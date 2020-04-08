package org.qora.test.btcacct;

import java.math.BigDecimal;
import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.qora.account.PrivateKeyAccount;
import org.qora.asset.Asset;
import org.qora.controller.Controller;
import org.qora.crosschain.BTCACCT;
import org.qora.crypto.Crypto;
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

public class DeployAT {

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: DeployAT <your Qortal PRIVATE key> <QORT amount> <redeem Qortal address> <HASH160-of-secret> <locktime> (<initial QORT payout>)"));
		System.err.println(String.format("example: DeployAT "
				+ "AdTd9SUEYSdTW8mgK3Gu72K97bCHGdUwi2VvLNjUohot \\\n"
				+ "\t3.1415 \\\n"
				+ "\tQgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v \\\n"
				+ "\td1b64100879ad93ceaa3c15929b6fe8550f54967 \\\n"
				+ "\t1585920000 \\\n"
				+ "\t0.0001"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 5 || args.length > 6)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Settings.fileInstance("settings-test.json");

		byte[] refundPrivateKey = null;
		BigDecimal qortAmount = null;
		String redeemAddress = null;
		byte[] secretHash = null;
		int lockTime = 0;
		BigDecimal initialPayout = BigDecimal.ZERO.setScale(8);

		int argIndex = 0;
		try {
			refundPrivateKey = Base58.decode(args[argIndex++]);
			if (refundPrivateKey.length != 32)
				usage("Refund private key must be 32 bytes");

			qortAmount = new BigDecimal(args[argIndex++]);
			if (qortAmount.signum() <= 0)
				usage("QORT amount must be positive");

			redeemAddress = args[argIndex++];
			if (!Crypto.isValidAddress(redeemAddress))
				usage("Redeem address invalid");

			secretHash = HashCode.fromString(args[argIndex++]).asBytes();
			if (secretHash.length != 20)
				usage("Hash of secret must be 20 bytes");

			lockTime = Integer.parseInt(args[argIndex++]);

			if (args.length > argIndex)
				initialPayout = new BigDecimal(args[argIndex++]).setScale(8);
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		try {
			RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(Controller.getRepositoryUrl());
			RepositoryManager.setRepositoryFactory(repositoryFactory);
		} catch (DataException e) {
			throw new RuntimeException("Repository startup issue: " + e.getMessage());
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			System.out.println("Confirm the following is correct based on the info you've given:");

			PrivateKeyAccount refundAccount = new PrivateKeyAccount(repository, refundPrivateKey);
			System.out.println(String.format("Refund Qortal address: %s", refundAccount.getAddress()));

			System.out.println(String.format("QORT amount (INCLUDING FEES): %s", qortAmount.toPlainString()));

			System.out.println(String.format("HASH160 of secret: %s", HashCode.fromBytes(secretHash)));

			System.out.println(String.format("Redeem Qortal address: %s", redeemAddress));

			// New/derived info

			System.out.println("\nCHECKING info from other party:");

			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneId.systemDefault()), lockTime));
			System.out.println("Make sure this is BEFORE P2SH lockTime to allow you to refund AT before P2SH refunded");

			// Deploy AT
			final int BLOCK_TIME = 60; // seconds
			final int refundTimeout = (lockTime - (int) (System.currentTimeMillis() / 1000L)) / BLOCK_TIME; 

			byte[] creationBytes = BTCACCT.buildQortalAT(secretHash, redeemAddress, refundTimeout, initialPayout);
			System.out.println("CIYAM AT creation bytes: " + HashCode.fromBytes(creationBytes).toString());

			long txTimestamp = System.currentTimeMillis();
			byte[] lastReference = refundAccount.getLastReference();

			if (lastReference == null) {
				System.err.println(String.format("Qortal account %s has no last reference", refundAccount.getAddress()));
				System.exit(2);
			}

			BigDecimal fee = BigDecimal.ZERO;
			BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, refundAccount.getPublicKey(), fee, null);
			TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, "QORT-BTC", "QORT-BTC ACCT", "", "", creationBytes, qortAmount, Asset.QORT);

			Transaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);

			fee = deployAtTransaction.calcRecommendedFee();
			deployAtTransactionData.setFee(fee);

			deployAtTransaction.sign(refundAccount);

			byte[] signedBytes = null;
			try {
				signedBytes = TransactionTransformer.toBytes(deployAtTransactionData);
			} catch (TransformationException e) {
				System.err.println(String.format("Unable to convert transaction to base58: %s", e.getMessage()));
				System.exit(2);
			}

			System.out.println(String.format("\nSigned transaction in base58, ready for POST /transactions/process:\n%s\n", Base58.encode(signedBytes)));
		} catch (NumberFormatException e) {
			usage(String.format("Number format exception: %s", e.getMessage()));
		} catch (DataException e) {
			throw new RuntimeException("Repository issue: " + e.getMessage());
		}
	}

}
