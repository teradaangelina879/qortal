package org.qortal.test.btcacct;

import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.List;

import org.ciyam.at.MachineState;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.at.QortalAtLoggerFactory;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.MessageTransaction;

import com.google.common.hash.HashCode;

public class AtTests extends Common {

	public static final byte[] secret = "This string is exactly 32 bytes!".getBytes();
	public static final byte[] secretHash = Crypto.hash160(secret); // daf59884b4d1aec8c1b17102530909ee43c0151a
	public static final int refundTimeout = 10; // blocks
	public static final BigDecimal initialPayout = new BigDecimal("0.001").setScale(8);
	public static final BigDecimal redeemAmount = new BigDecimal("80.4020").setScale(8);
	public static final BigDecimal fundingAmount = new BigDecimal("123.456").setScale(8);

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCompile() {
		String redeemAddress = Common.getTestAccount(null, "chloe").getAddress();

		byte[] creationBytes = BTCACCT.buildQortalAT(secretHash, redeemAddress, refundTimeout, initialPayout, redeemAmount);
		System.out.println("CIYAM AT creation bytes: " + HashCode.fromBytes(creationBytes).toString());
	}

	@Test
	public void testDeploy() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			BigDecimal deployersInitialBalance = deployer.getBalance(Asset.QORT);
			BigDecimal recipientsInitialBalance = recipient.getBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer, recipient);

			BigDecimal expectedBalance = deployersInitialBalance.subtract(fundingAmount).subtract(deployAtTransaction.getTransactionData().getFee());
			BigDecimal actualBalance = deployer.getBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Deployer's post-deployment balance incorrect", expectedBalance, actualBalance);

			expectedBalance = fundingAmount;
			actualBalance = deployAtTransaction.getATAccount().getConfirmedBalance(Asset.QORT);

			Common.assertEqualBigDecimals("AT's post-deployment balance incorrect", expectedBalance, actualBalance);

			expectedBalance = recipientsInitialBalance;
			actualBalance = recipient.getBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Recipient's post-deployment balance incorrect", expectedBalance, actualBalance);

			// Test orphaning
			BlockUtils.orphanLastBlock(repository);

			expectedBalance = deployersInitialBalance;
			actualBalance = deployer.getBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Deployer's post-orphan/pre-deployment balance incorrect", expectedBalance, actualBalance);

			expectedBalance = BigDecimal.ZERO;
			actualBalance = deployAtTransaction.getATAccount().getConfirmedBalance(Asset.QORT);

			Common.assertEqualBigDecimals("AT's post-orphan/pre-deployment balance incorrect", expectedBalance, actualBalance);

			expectedBalance = recipientsInitialBalance;
			actualBalance = recipient.getBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Recipient's post-orphan/pre-deployment balance incorrect", expectedBalance, actualBalance);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testInitialPayment() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			BigDecimal deployersInitialBalance = deployer.getBalance(Asset.QORT);
			BigDecimal recipientsInitialBalance = recipient.getBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer, recipient);

			// Initial payment should happen 1st block after deployment
			BlockUtils.mintBlock(repository);

			BigDecimal expectedBalance = recipientsInitialBalance.add(initialPayout);
			BigDecimal actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Recipient's post-initial-payout balance incorrect", expectedBalance, actualBalance);

			// Test orphaning
			BlockUtils.orphanLastBlock(repository);

			expectedBalance = recipientsInitialBalance;
			actualBalance = recipient.getBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Recipient's pre-initial-payout balance incorrect", expectedBalance, actualBalance);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testAutomaticRefund() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			BigDecimal deployersInitialBalance = deployer.getBalance(Asset.QORT);
			BigDecimal recipientsInitialBalance = recipient.getBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer, recipient);

			BigDecimal deployAtFee = deployAtTransaction.getTransactionData().getFee();
			BigDecimal deployersPostDeploymentBalance = deployersInitialBalance.subtract(fundingAmount).subtract(deployAtFee);

			checkAtRefund(repository, deployer, deployersInitialBalance, deployAtFee);

			// Test orphaning
			BlockUtils.orphanLastBlock(repository);

			BigDecimal expectedBalance = deployersPostDeploymentBalance;
			BigDecimal actualBalance = deployer.getBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Deployer's post-orphan/pre-refund balance incorrect", expectedBalance, actualBalance);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testCorrectSecretCorrectSender() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			BigDecimal deployersInitialBalance = deployer.getBalance(Asset.QORT);
			BigDecimal recipientsInitialBalance = recipient.getBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer, recipient);
			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			// Send correct secret to AT
			MessageTransaction messageTransaction = sendSecret(repository, recipient, secret, atAddress);

			// AT should send funds in the next block
			ATStateData preRedeemAtStateData = repository.getATRepository().getLatestATState(atAddress);
			BlockUtils.mintBlock(repository);

			BigDecimal expectedBalance = recipientsInitialBalance.add(initialPayout).subtract(messageTransaction.getTransactionData().getFee()).add(redeemAmount);
			BigDecimal actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Recipent's post-redeem balance incorrect", expectedBalance, actualBalance);

			// Orphan redeem
			BlockUtils.orphanLastBlock(repository);

			expectedBalance = recipientsInitialBalance.add(initialPayout).subtract(messageTransaction.getTransactionData().getFee());
			actualBalance = recipient.getBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Recipent's post-orphan/pre-redeem balance incorrect", expectedBalance, actualBalance);

			// Check AT state
			ATStateData postOrphanAtStateData = repository.getATRepository().getLatestATState(atAddress);

			assertTrue("AT states mismatch", Arrays.equals(preRedeemAtStateData.getStateData(), postOrphanAtStateData.getStateData()));
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testCorrectSecretIncorrectSender() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount bystander = Common.getTestAccount(repository, "bob");

			BigDecimal deployersInitialBalance = deployer.getBalance(Asset.QORT);
			BigDecimal recipientsInitialBalance = recipient.getBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer, recipient);
			BigDecimal deployAtFee = deployAtTransaction.getTransactionData().getFee();

			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			// Send correct secret to AT, but from wrong account
			MessageTransaction messageTransaction = sendSecret(repository, bystander, secret, atAddress);

			// AT should NOT send funds in the next block
			ATStateData preRedeemAtStateData = repository.getATRepository().getLatestATState(atAddress);
			BlockUtils.mintBlock(repository);

			BigDecimal expectedBalance = recipientsInitialBalance.add(initialPayout);
			BigDecimal actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Recipent's balance incorrect", expectedBalance, actualBalance);

			checkAtRefund(repository, deployer, deployersInitialBalance, deployAtFee);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testIncorrectSecretCorrectSender() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			BigDecimal deployersInitialBalance = deployer.getBalance(Asset.QORT);
			BigDecimal recipientsInitialBalance = recipient.getBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer, recipient);
			BigDecimal deployAtFee = deployAtTransaction.getTransactionData().getFee();

			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			// Send correct secret to AT, but from wrong account
			byte[] wrongSecret = Crypto.digest(secret);
			MessageTransaction messageTransaction = sendSecret(repository, recipient, wrongSecret, atAddress);

			// AT should NOT send funds in the next block
			ATStateData preRedeemAtStateData = repository.getATRepository().getLatestATState(atAddress);
			BlockUtils.mintBlock(repository);

			BigDecimal expectedBalance = recipientsInitialBalance.add(initialPayout).subtract(messageTransaction.getTransactionData().getFee());
			BigDecimal actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			Common.assertEqualBigDecimals("Recipent's balance incorrect", expectedBalance, actualBalance);

			checkAtRefund(repository, deployer, deployersInitialBalance, deployAtFee);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testDescribeDeployed() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			BigDecimal deployersInitialBalance = deployer.getBalance(Asset.QORT);
			BigDecimal recipientsInitialBalance = recipient.getBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer, recipient);

			List<ATData> executableAts = repository.getATRepository().getAllExecutableATs();
			
			for (ATData atData : executableAts) {
				String atAddress = atData.getATAddress();
				byte[] codeBytes = atData.getCodeBytes();
				byte[] codeHash = Crypto.digest(codeBytes);

				System.out.println(String.format("%s: code length: %d byte%s, code hash: %s",
						atAddress,
						codeBytes.length,
						(codeBytes.length != 1 ? "s": ""),
						HashCode.fromBytes(codeHash)));

				// Not one of ours?
				if (!Arrays.equals(codeHash, BTCACCT.CODE_BYTES_HASH))
					continue;

				ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
				byte[] stateData = atStateData.getStateData();

				QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();
				byte[] dataBytes = MachineState.extractDataBytes(loggerFactory, stateData);

				BTCACCT.AtConstants atConstants = BTCACCT.extractAtConstants(dataBytes);

				long autoRefundTimestamp = atData.getCreation() + atConstants.refundMinutes * 60 * 1000L;

				String autoRefundString = LocalDateTime.ofInstant(Instant.ofEpochMilli(autoRefundTimestamp), ZoneOffset.UTC).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM));
				System.out.println(String.format("%s:\n"
						+ "\tcreator: %s,\n"
						+ "\tHASH160 of secret: %s,\n"
						+ "\trecipient: %s,\n"
						+ "\tinitial payout: %s QORT,\n"
						+ "\tredeem payout: %s QORT,\n"
						+ "\tauto-refund at: %s (local time)",
						atAddress,
						Crypto.toAddress(atData.getCreatorPublicKey()),
						HashCode.fromBytes(atConstants.secretHash).toString().substring(0, 40),
						atConstants.recipient,
						atConstants.initialPayout.toPlainString(),
						atConstants.redeemPayout.toPlainString(),
						autoRefundString));
			}
		}
	}

	private DeployAtTransaction doDeploy(Repository repository, PrivateKeyAccount deployer, Account recipient) throws DataException {
		byte[] creationBytes = BTCACCT.buildQortalAT(secretHash, recipient.getAddress(), refundTimeout, initialPayout, redeemAmount);

		long txTimestamp = System.currentTimeMillis();
		byte[] lastReference = deployer.getLastReference();

		if (lastReference == null) {
			System.err.println(String.format("Qortal account %s has no last reference", deployer.getAddress()));
			System.exit(2);
		}

		BigDecimal fee = BigDecimal.ZERO;
		String name = "QORT-BTC cross-chain trade";
		String description = String.format("Qortal-Bitcoin cross-chain trade between %s and %s", deployer.getAddress(), recipient.getAddress());
		String atType = "ACCT";
		String tags = "QORT-BTC ACCT";

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, deployer.getPublicKey(), fee, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, atType, tags, creationBytes, fundingAmount, Asset.QORT);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);

		fee = deployAtTransaction.calcRecommendedFee();
		deployAtTransactionData.setFee(fee);

		TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

		return deployAtTransaction;
	}

	private MessageTransaction sendSecret(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient) throws DataException {
		long txTimestamp = System.currentTimeMillis();
		byte[] lastReference = sender.getLastReference();

		if (lastReference == null) {
			System.err.println(String.format("Qortal account %s has no last reference", sender.getAddress()));
			System.exit(2);
		}

		BigDecimal fee = BigDecimal.ZERO;
		BigDecimal amount = BigDecimal.ZERO;

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, sender.getPublicKey(), fee, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, 4, recipient, Asset.QORT, amount, data, false, false);

		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);

		fee = messageTransaction.calcRecommendedFee();
		messageTransactionData.setFee(fee);

		TransactionUtils.signAndMint(repository, messageTransactionData, sender);

		return messageTransaction;
	}

	private void checkAtRefund(Repository repository, Account deployer, BigDecimal deployersInitialBalance, BigDecimal deployAtFee) throws DataException {
		BigDecimal deployersPostDeploymentBalance = deployersInitialBalance.subtract(fundingAmount).subtract(deployAtFee);

		// AT should automatically refund deployer after 'refundTimeout' blocks
		for (int blockCount = 0; blockCount <= refundTimeout; ++blockCount)
			BlockUtils.mintBlock(repository);

		// We don't bother to exactly calculate QORT spent running AT for several blocks, but we do know the expected range
		BigDecimal expectedMinimumBalance = deployersPostDeploymentBalance;
		BigDecimal expectedMaximumBalance = deployersInitialBalance.subtract(deployAtFee).subtract(initialPayout);

		BigDecimal actualBalance = deployer.getConfirmedBalance(Asset.QORT);

		assertTrue(String.format("Deployer's balance %s should be above minimum %s", actualBalance.toPlainString(), expectedMinimumBalance.toPlainString()), actualBalance.compareTo(expectedMinimumBalance) > 0);
		assertTrue(String.format("Deployer's balance %s should be below maximum %s", actualBalance.toPlainString(), expectedMaximumBalance.toPlainString()), actualBalance.compareTo(expectedMaximumBalance) < 0);
	}

}
