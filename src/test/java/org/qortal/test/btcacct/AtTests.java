package org.qortal.test.btcacct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.bitcoinj.core.Base58;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
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
import org.qortal.utils.Amounts;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;

public class AtTests extends Common {

	public static final byte[] secret = "This string is exactly 32 bytes!".getBytes();
	public static final byte[] secretHash = Crypto.hash160(secret); // daf59884b4d1aec8c1b17102530909ee43c0151a
	public static final int refundTimeout = 10; // blocks
	public static final long initialPayout = 100000L;
	public static final long redeemAmount = 80_40200000L;
	public static final long fundingAmount = 123_45600000L;
	public static final long bitcoinAmount = 864200L;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCompile() {
		Account deployer = Common.getTestAccount(null, "chloe");

		byte[] creationBytes = BTCACCT.buildQortalAT(deployer.getAddress(), secretHash, refundTimeout, initialPayout, redeemAmount, bitcoinAmount);
		System.out.println("CIYAM AT creation bytes: " + HashCode.fromBytes(creationBytes).toString());
	}

	@Test
	public void testDeploy() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			long deployersInitialBalance = deployer.getConfirmedBalance(Asset.QORT);
			long recipientsInitialBalance = recipient.getConfirmedBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer);

			long expectedBalance = deployersInitialBalance - fundingAmount - deployAtTransaction.getTransactionData().getFee();
			long actualBalance = deployer.getConfirmedBalance(Asset.QORT);

			assertEquals("Deployer's post-deployment balance incorrect", expectedBalance, actualBalance);

			expectedBalance = fundingAmount;
			actualBalance = deployAtTransaction.getATAccount().getConfirmedBalance(Asset.QORT);

			assertEquals("AT's post-deployment balance incorrect", expectedBalance, actualBalance);

			expectedBalance = recipientsInitialBalance;
			actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			assertEquals("Recipient's post-deployment balance incorrect", expectedBalance, actualBalance);

			// Test orphaning
			BlockUtils.orphanLastBlock(repository);

			expectedBalance = deployersInitialBalance;
			actualBalance = deployer.getConfirmedBalance(Asset.QORT);

			assertEquals("Deployer's post-orphan/pre-deployment balance incorrect", expectedBalance, actualBalance);

			expectedBalance = 0;
			actualBalance = deployAtTransaction.getATAccount().getConfirmedBalance(Asset.QORT);

			assertEquals("AT's post-orphan/pre-deployment balance incorrect", expectedBalance, actualBalance);

			expectedBalance = recipientsInitialBalance;
			actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			assertEquals("Recipient's post-orphan/pre-deployment balance incorrect", expectedBalance, actualBalance);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testOfferCancel() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			long deployersInitialBalance = deployer.getConfirmedBalance(Asset.QORT);
			long recipientsInitialBalance = recipient.getConfirmedBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer);
			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			long deployAtFee = deployAtTransaction.getTransactionData().getFee();
			long deployersPostDeploymentBalance = deployersInitialBalance - fundingAmount - deployAtFee;

			// Send creator's address to AT
			byte[] recipientAddressBytes = Bytes.ensureCapacity(Base58.decode(deployer.getAddress()), 32, 0);
			MessageTransaction messageTransaction = sendMessage(repository, deployer, recipientAddressBytes, atAddress);
			long messageFee = messageTransaction.getTransactionData().getFee();

			// Refund should happen 1st block after receiving recipient address
			BlockUtils.mintBlock(repository);

			long expectedMinimumBalance = deployersPostDeploymentBalance;
			long expectedMaximumBalance = deployersInitialBalance - deployAtFee - messageFee;

			long actualBalance = deployer.getConfirmedBalance(Asset.QORT);

			assertTrue(String.format("Deployer's balance %s should be above minimum %s", actualBalance, expectedMinimumBalance), actualBalance > expectedMinimumBalance);
			assertTrue(String.format("Deployer's balance %s should be below maximum %s", actualBalance, expectedMaximumBalance), actualBalance < expectedMaximumBalance);

			describeAt(repository, atAddress);

			// Test orphaning
			BlockUtils.orphanLastBlock(repository);

			long expectedBalance = deployersPostDeploymentBalance - messageFee;
			actualBalance = deployer.getConfirmedBalance(Asset.QORT);

			assertEquals("Deployer's post-orphan/pre-refund balance incorrect", expectedBalance, actualBalance);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testInitialPayment() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			long deployersInitialBalance = deployer.getConfirmedBalance(Asset.QORT);
			long recipientsInitialBalance = recipient.getConfirmedBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer);
			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			// Send recipient's address to AT
			byte[] recipientAddressBytes = Bytes.ensureCapacity(Base58.decode(recipient.getAddress()), 32, 0);
			MessageTransaction messageTransaction = sendMessage(repository, deployer, recipientAddressBytes, atAddress);

			// Initial payment should happen 1st block after receiving recipient address
			BlockUtils.mintBlock(repository);

			long expectedBalance = recipientsInitialBalance + initialPayout;
			long actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			assertEquals("Recipient's post-initial-payout balance incorrect", expectedBalance, actualBalance);

			describeAt(repository, atAddress);

			// Test orphaning
			BlockUtils.orphanLastBlock(repository);

			expectedBalance = recipientsInitialBalance;
			actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			assertEquals("Recipient's pre-initial-payout balance incorrect", expectedBalance, actualBalance);
		}
	}

	// TEST SENDING RECIPIENT ADDRESS BUT NOT FROM AT CREATOR (SHOULD BE IGNORED)
	@SuppressWarnings("unused")
	@Test
	public void testIncorrectTradeSender() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount bystander = Common.getTestAccount(repository, "bob");

			long deployersInitialBalance = deployer.getConfirmedBalance(Asset.QORT);
			long recipientsInitialBalance = recipient.getConfirmedBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer);
			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			// Send recipient's address to AT BUT NOT FROM AT CREATOR
			byte[] recipientAddressBytes = Bytes.ensureCapacity(Base58.decode(recipient.getAddress()), 32, 0);
			MessageTransaction messageTransaction = sendMessage(repository, bystander, recipientAddressBytes, atAddress);

			// Initial payment should NOT happen
			BlockUtils.mintBlock(repository);

			long expectedBalance = recipientsInitialBalance;
			long actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			assertEquals("Recipient's post-initial-payout balance incorrect", expectedBalance, actualBalance);

			describeAt(repository, atAddress);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testAutomaticTradeRefund() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			long deployersInitialBalance = deployer.getConfirmedBalance(Asset.QORT);
			long recipientsInitialBalance = recipient.getConfirmedBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer);
			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			// Send recipient's address to AT
			byte[] recipientAddressBytes = Bytes.ensureCapacity(Base58.decode(recipient.getAddress()), 32, 0);
			MessageTransaction messageTransaction = sendMessage(repository, deployer, recipientAddressBytes, atAddress);

			// Initial payment should happen 1st block after receiving recipient address
			BlockUtils.mintBlock(repository);

			long deployAtFee = deployAtTransaction.getTransactionData().getFee();
			long messageFee = messageTransaction.getTransactionData().getFee();
			long deployersPostDeploymentBalance = deployersInitialBalance - fundingAmount - deployAtFee - messageFee;

			checkTradeRefund(repository, deployer, deployersInitialBalance, deployAtFee);

			describeAt(repository, atAddress);

			// Test orphaning
			BlockUtils.orphanLastBlock(repository);
			BlockUtils.orphanLastBlock(repository);

			long expectedBalance = deployersPostDeploymentBalance;
			long actualBalance = deployer.getConfirmedBalance(Asset.QORT);

			assertEquals("Deployer's post-orphan/pre-refund balance incorrect", expectedBalance, actualBalance);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testCorrectSecretCorrectSender() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			long deployersInitialBalance = deployer.getConfirmedBalance(Asset.QORT);
			long recipientsInitialBalance = recipient.getConfirmedBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer);
			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			// Send recipient's address to AT
			byte[] recipientAddressBytes = Bytes.ensureCapacity(Base58.decode(recipient.getAddress()), 32, 0);
			MessageTransaction messageTransaction = sendMessage(repository, deployer, recipientAddressBytes, atAddress);

			// Initial payment should happen 1st block after receiving recipient address
			BlockUtils.mintBlock(repository);

			// Send correct secret to AT
			messageTransaction = sendMessage(repository, recipient, secret, atAddress);

			// AT should send funds in the next block
			ATStateData preRedeemAtStateData = repository.getATRepository().getLatestATState(atAddress);
			BlockUtils.mintBlock(repository);

			long expectedBalance = recipientsInitialBalance + initialPayout - messageTransaction.getTransactionData().getFee() + redeemAmount;
			long actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			assertEquals("Recipent's post-redeem balance incorrect", expectedBalance, actualBalance);

			describeAt(repository, atAddress);

			// Orphan redeem
			BlockUtils.orphanLastBlock(repository);

			expectedBalance = recipientsInitialBalance + initialPayout - messageTransaction.getTransactionData().getFee();
			actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			assertEquals("Recipent's post-orphan/pre-redeem balance incorrect", expectedBalance, actualBalance);

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

			long deployersInitialBalance = deployer.getConfirmedBalance(Asset.QORT);
			long recipientsInitialBalance = recipient.getConfirmedBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer);
			long deployAtFee = deployAtTransaction.getTransactionData().getFee();

			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			// Send recipient's address to AT
			byte[] recipientAddressBytes = Bytes.ensureCapacity(Base58.decode(recipient.getAddress()), 32, 0);
			MessageTransaction messageTransaction = sendMessage(repository, deployer, recipientAddressBytes, atAddress);

			// Initial payment should happen 1st block after receiving recipient address
			BlockUtils.mintBlock(repository);

			// Send correct secret to AT, but from wrong account
			messageTransaction = sendMessage(repository, bystander, secret, atAddress);

			// AT should NOT send funds in the next block
			ATStateData preRedeemAtStateData = repository.getATRepository().getLatestATState(atAddress);
			BlockUtils.mintBlock(repository);

			long expectedBalance = recipientsInitialBalance + initialPayout;
			long actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			assertEquals("Recipent's balance incorrect", expectedBalance, actualBalance);

			describeAt(repository, atAddress);

			checkTradeRefund(repository, deployer, deployersInitialBalance, deployAtFee);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testIncorrectSecretCorrectSender() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			long deployersInitialBalance = deployer.getConfirmedBalance(Asset.QORT);
			long recipientsInitialBalance = recipient.getConfirmedBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer);
			long deployAtFee = deployAtTransaction.getTransactionData().getFee();

			Account at = deployAtTransaction.getATAccount();
			String atAddress = at.getAddress();

			// Send recipient's address to AT
			byte[] recipientAddressBytes = Bytes.ensureCapacity(Base58.decode(recipient.getAddress()), 32, 0);
			MessageTransaction messageTransaction = sendMessage(repository, deployer, recipientAddressBytes, atAddress);

			// Initial payment should happen 1st block after receiving recipient address
			BlockUtils.mintBlock(repository);

			// Send correct secret to AT, but from wrong account
			byte[] wrongSecret = Crypto.digest(secret);
			messageTransaction = sendMessage(repository, recipient, wrongSecret, atAddress);

			// AT should NOT send funds in the next block
			ATStateData preRedeemAtStateData = repository.getATRepository().getLatestATState(atAddress);
			BlockUtils.mintBlock(repository);

			long expectedBalance = recipientsInitialBalance + initialPayout - messageTransaction.getTransactionData().getFee();
			long actualBalance = recipient.getConfirmedBalance(Asset.QORT);

			assertEquals("Recipent's balance incorrect", expectedBalance, actualBalance);

			describeAt(repository, atAddress);

			checkTradeRefund(repository, deployer, deployersInitialBalance, deployAtFee);
		}
	}

	@SuppressWarnings("unused")
	@Test
	public void testDescribeDeployed() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "dilbert");

			long deployersInitialBalance = deployer.getConfirmedBalance(Asset.QORT);
			long recipientsInitialBalance = recipient.getConfirmedBalance(Asset.QORT);

			DeployAtTransaction deployAtTransaction = doDeploy(repository, deployer);

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

				describeAt(repository, atAddress);
			}
		}
	}

	private DeployAtTransaction doDeploy(Repository repository, PrivateKeyAccount deployer) throws DataException {
		byte[] creationBytes = BTCACCT.buildQortalAT(deployer.getAddress(), secretHash, refundTimeout, initialPayout, redeemAmount, bitcoinAmount);

		long txTimestamp = System.currentTimeMillis();
		byte[] lastReference = deployer.getLastReference();

		if (lastReference == null) {
			System.err.println(String.format("Qortal account %s has no last reference", deployer.getAddress()));
			System.exit(2);
		}

		Long fee = null;
		String name = "QORT-BTC cross-chain trade";
		String description = String.format("Qortal-Bitcoin cross-chain trade");
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

	private MessageTransaction sendMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient) throws DataException {
		long txTimestamp = System.currentTimeMillis();
		byte[] lastReference = sender.getLastReference();

		if (lastReference == null) {
			System.err.println(String.format("Qortal account %s has no last reference", sender.getAddress()));
			System.exit(2);
		}

		Long fee = null;
		long amount = 0;

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, sender.getPublicKey(), fee, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, 4, recipient, Asset.QORT, amount, data, false, false);

		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);

		fee = messageTransaction.calcRecommendedFee();
		messageTransactionData.setFee(fee);

		TransactionUtils.signAndMint(repository, messageTransactionData, sender);

		return messageTransaction;
	}

	private void checkTradeRefund(Repository repository, Account deployer, long deployersInitialBalance, long deployAtFee) throws DataException {
		long deployersPostDeploymentBalance = deployersInitialBalance - fundingAmount - deployAtFee;

		// AT should automatically refund deployer after 'refundTimeout' blocks
		for (int blockCount = 0; blockCount <= refundTimeout; ++blockCount)
			BlockUtils.mintBlock(repository);

		// We don't bother to exactly calculate QORT spent running AT for several blocks, but we do know the expected range
		long expectedMinimumBalance = deployersPostDeploymentBalance;
		long expectedMaximumBalance = deployersInitialBalance - deployAtFee - initialPayout;

		long actualBalance = deployer.getConfirmedBalance(Asset.QORT);

		assertTrue(String.format("Deployer's balance %s should be above minimum %s", actualBalance, expectedMinimumBalance), actualBalance > expectedMinimumBalance);
		assertTrue(String.format("Deployer's balance %s should be below maximum %s", actualBalance, expectedMaximumBalance), actualBalance < expectedMaximumBalance);
	}

	private void describeAt(Repository repository, String atAddress) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		CrossChainTradeData tradeData = BTCACCT.populateTradeData(repository, atData);

		Function<Long, String> epochMilliFormatter = (timestamp) -> LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM));
		int currentBlockHeight = repository.getBlockRepository().getBlockchainHeight();

		System.out.print(String.format("%s:\n"
				+ "\tcreator: %s,\n"
				+ "\tcreation timestamp: %s,\n"
				+ "\tcurrent balance: %s QORT,\n"
				+ "\tHASH160 of secret: %s,\n"
				+ "\tinitial payout: %s QORT,\n"
				+ "\tredeem payout: %s QORT,\n"
				+ "\texpected bitcoin: %s BTC,\n"
				+ "\ttrade timeout: %d minutes (from trade start),\n"
				+ "\tcurrent block height: %d,\n",
				tradeData.qortalAtAddress,
				tradeData.qortalCreator,
				epochMilliFormatter.apply(tradeData.creationTimestamp),
				Amounts.prettyAmount(tradeData.qortBalance),
				HashCode.fromBytes(tradeData.secretHash).toString().substring(0, 40),
				Amounts.prettyAmount(tradeData.initialPayout),
				Amounts.prettyAmount(tradeData.redeemPayout),
				Amounts.prettyAmount(tradeData.expectedBitcoin),
				tradeData.tradeRefundTimeout,
				currentBlockHeight));

		// Are we in 'offer' or 'trade' stage?
		if (tradeData.tradeRefundHeight == null) {
			// Offer
			System.out.println(String.format("\tstatus: 'offer mode'"));
		} else {
			// Trade
			System.out.println(String.format("\tstatus: 'trade mode',\n"
					+ "\ttrade timeout: block %d,\n"
					+ "\tBitcoin P2SH nLockTime: %d (%s),\n"
					+ "\ttrade recipient: %s",
					tradeData.tradeRefundHeight,
					tradeData.lockTime, epochMilliFormatter.apply(tradeData.lockTime * 1000L),
					tradeData.qortalRecipient));
		}
	}

}
