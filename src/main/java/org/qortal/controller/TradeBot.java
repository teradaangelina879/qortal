package org.qortal.controller;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.model.TradeBotCreateRequest;
import org.qortal.asset.Asset;
import org.qortal.crosschain.BTC;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crosschain.BTCP2SH;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.DeployAtTransactionTransformer;
import org.qortal.utils.NTP;

public class TradeBot {

	private static final Logger LOGGER = LogManager.getLogger(TradeBot.class);
	private static final Random RANDOM = new SecureRandom();
	private static final long FEE_AMOUNT = 1000L;

	private static TradeBot instance;

	/** To help ensure only TradeBot is only active on one thread. */
	private AtomicBoolean activeFlag = new AtomicBoolean(false);

	private TradeBot() {
	}

	public static synchronized TradeBot getInstance() {
		if (instance == null)
			instance = new TradeBot();

		return instance;
	}

	public static byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) throws DataException {
		byte[] tradePrivateKey = generateTradePrivateKey();
		byte[] secretB = generateSecret();
		byte[] hashOfSecretB = Crypto.hash160(secretB);

		byte[] tradeNativePublicKey = deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
		String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		PublicKeyAccount creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);

		// Deploy AT
		long timestamp = NTP.getTime();
		byte[] reference = creator.getLastReference();
		long fee = 0L;
		byte[] signature = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, creator.getPublicKey(), fee, signature);

		String name = "QORT/BTC ACCT";
		String description = "QORT/BTC cross-chain trade";
		String aTType = "ACCT";
		String tags = "ACCT QORT BTC";
		byte[] creationBytes = BTCACCT.buildQortalAT(tradeNativeAddress, tradeForeignPublicKeyHash, hashOfSecretB, tradeBotCreateRequest.qortAmount, tradeBotCreateRequest.bitcoinAmount, tradeBotCreateRequest.tradeTimeout);
		long amount = tradeBotCreateRequest.fundingQortAmount;

		DeployAtTransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, aTType, tags, creationBytes, amount, Asset.QORT);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		fee = deployAtTransaction.calcRecommendedFee();
		deployAtTransactionData.setFee(fee);

		DeployAtTransaction.ensureATAddress(deployAtTransactionData);
		String atAddress = deployAtTransactionData.getAtAddress();

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, TradeBotData.State.BOB_WAITING_FOR_AT_CONFIRM,
				atAddress,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				secretB, hashOfSecretB,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.bitcoinAmount, null, null, null);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		LOGGER.info(() -> String.format("Built AT %s. Waiting for deployment", atAddress));

		// Return to user for signing and broadcast as we don't have their Qortal private key
		try {
			return DeployAtTransactionTransformer.toBytes(deployAtTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform DEPLOY_AT transaction?", e);
		}
	}

	public static boolean startResponse(Repository repository, CrossChainTradeData crossChainTradeData, String xprv58) throws DataException {
		byte[] tradePrivateKey = generateTradePrivateKey();
		byte[] secretA = generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);

		byte[] tradeNativePublicKey = deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
		String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		// We need to generate lockTime-A: halfway of refundTimeout from now
		int lockTimeA = crossChainTradeData.tradeTimeout * 60 + (int) (NTP.getTime() / 1000L);

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, TradeBotData.State.ALICE_WAITING_FOR_P2SH_A,
				crossChainTradeData.qortalAtAddress,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				secretA, hashOfSecretA,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				crossChainTradeData.expectedBitcoin, xprv58, null, lockTimeA);

		// Check we have enough funds via xprv58 to fund both P2SHs to cover expectedBitcoin
		String tradeForeignAddress = BTC.getInstance().pkhToAddress(tradeForeignPublicKeyHash);

		long totalFundsRequired = crossChainTradeData.expectedBitcoin + FEE_AMOUNT /* P2SH-A */ + FEE_AMOUNT /* P2SH-B */;

		Transaction fundingCheckTransaction = BTC.getInstance().buildSpend(xprv58, tradeForeignAddress, totalFundsRequired);
		if (fundingCheckTransaction == null)
			return false;

		// P2SH-A to be funded
		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeForeignPublicKeyHash, lockTimeA, crossChainTradeData.creatorBitcoinPKH, hashOfSecretA);
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		// Fund P2SH-A
		Transaction p2shFundingTransaction = BTC.getInstance().buildSpend(tradeBotData.getXprv58(), p2shAddress, crossChainTradeData.expectedBitcoin + FEE_AMOUNT);
		if (!BTC.getInstance().broadcastTransaction(p2shFundingTransaction)) {
			// We couldn't fund P2SH-A at this time
			LOGGER.debug(() -> String.format("Couldn't broadcast P2SH-A funding transaction?"));
			return false;
		}

		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		LOGGER.info(() -> String.format("Funding P2SH-A %s. Waiting for confirmation", p2shAddress));

		return true;
	}

	private static byte[] generateTradePrivateKey() {
		byte[] seed = new byte[32];
		RANDOM.nextBytes(seed);
		return seed;
	}

	private static byte[] deriveTradeNativePublicKey(byte[] privateKey) {
		return PrivateKeyAccount.toPublicKey(privateKey);
	}

	private static byte[] deriveTradeForeignPublicKey(byte[] privateKey) {
		return ECKey.fromPrivate(privateKey).getPubKey();
	}

	private static byte[] generateSecret() {
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		return secret;
	}

	public void onChainTipChange() {
		// No point doing anything on old/stale data
		if (!Controller.getInstance().isUpToDate())
			return;

		if (!activeFlag.compareAndSet(false, true))
			// Trade bot already active on another thread
			return;

		// Get repo for trade situations
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();

			for (TradeBotData tradeBotData : allTradeBotData) {
				repository.discardChanges();

				switch (tradeBotData.getState()) {
					case BOB_WAITING_FOR_AT_CONFIRM:
						handleBobWaitingForAtConfirm(repository, tradeBotData);
						break;

					case ALICE_WAITING_FOR_P2SH_A:
						handleAliceWaitingForP2shA(repository, tradeBotData);
						break;

					case BOB_WAITING_FOR_MESSAGE:
						handleBobWaitingForMessage(repository, tradeBotData);
						break;

					case ALICE_WAITING_FOR_AT_LOCK:
						handleAliceWaitingForAtLock(repository, tradeBotData);
						break;

					case BOB_WAITING_FOR_P2SH_B:
						handleBobWaitingForP2shB(repository, tradeBotData);
						break;

					case ALICE_WATCH_P2SH_B:
						handleAliceWatchingP2shB(repository, tradeBotData);
						break;

					case BOB_WAITING_FOR_AT_REDEEM:
						handleBobWaitingForAtRedeem(repository, tradeBotData);
						break;

					case ALICE_DONE:
					case BOB_DONE:
						break;

					case ALICE_REFUNDING_B:
						handleAliceRefundingP2shB(repository, tradeBotData);
						break;

					case ALICE_REFUNDING_A:
						handleAliceRefundingP2shA(repository, tradeBotData);
						break;

					case ALICE_REFUNDED:
					case BOB_REFUNDED:
						break;

					default:
						LOGGER.warn(() -> String.format("Unhandled trade-bot state %s", tradeBotData.getState().name()));
				}
			}
		} catch (DataException e) {
			LOGGER.error("Couldn't run trade bot due to repository issue", e);
		} finally {
			activeFlag.set(false);
		}
	}

	private void handleBobWaitingForAtConfirm(Repository repository, TradeBotData tradeBotData) throws DataException {
		if (!repository.getATRepository().exists(tradeBotData.getAtAddress()))
			return;

		tradeBotData.setState(TradeBotData.State.BOB_WAITING_FOR_MESSAGE);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		LOGGER.info(() -> String.format("AT %s confirmed ready. Waiting for trade message", tradeBotData.getAtAddress()));
	}

	private void handleAliceWaitingForP2shA(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		// If AT has finished then maybe Bob cancelled his trade offer
		if (atData.getIsFinished()) {
			// No point sending MESSAGE - might as well wait for refund
			tradeBotData.setState(TradeBotData.State.ALICE_REFUNDING_A);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();

			LOGGER.info(() -> String.format("AT %s cancelled. Refunding P2SH-A %s - aborting trade", tradeBotData.getAtAddress(), p2shAddress));

			return;
		}

		Long balance = BTC.getInstance().getBalance(p2shAddress);
		if (balance == null || balance < crossChainTradeData.expectedBitcoin) {
			if (balance != null && balance > 0)
				LOGGER.debug(() -> String.format("P2SH-A balance %s lower than expected %s", BTC.format(balance), BTC.format(crossChainTradeData.expectedBitcoin)));

			return;
		}

		// P2SH-A funding confirmed

		// Attempt to send MESSAGE to Bob's Qortal trade address
		byte[] messageData = BTCACCT.buildOfferMessage(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getHashOfSecret(), tradeBotData.getLockTimeA());

		PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
		MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, crossChainTradeData.qortalCreatorTradeAddress, messageData, false, false);

		messageTransaction.computeNonce();
		messageTransaction.sign(sender);

		// reset repository state to prevent deadlock
		repository.discardChanges();
		ValidationResult result = messageTransaction.importAsUnconfirmed();

		if (result != ValidationResult.OK) {
			LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: %s", messageTransaction.getRecipient(), result.name()));
			return;
		}

		tradeBotData.setState(TradeBotData.State.ALICE_WAITING_FOR_AT_LOCK);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		LOGGER.info(() -> String.format("P2SH-A %s funding confirmed. Messaged %s. Waiting for AT %s to lock to us",
				p2shAddress, crossChainTradeData.qortalCreatorTradeAddress, tradeBotData.getAtAddress()));
	}

	private void handleBobWaitingForMessage(Repository repository, TradeBotData tradeBotData) throws DataException {
		// Fetch AT so we can determine trade start timestamp
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}

		// If AT has finished then Bob likely cancelled his trade offer
		if (atData.getIsFinished()) {
			tradeBotData.setState(TradeBotData.State.BOB_REFUNDED);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();

			LOGGER.info(() -> String.format("AT %s cancelled - trading aborted", tradeBotData.getAtAddress()));

			return;
		}

		String address = tradeBotData.getTradeNativeAddress();
		List<MessageTransactionData> messageTransactionsData = repository.getTransactionRepository().getMessagesByRecipient(address, null, null, null);

		final byte[] originalLastTransactionSignature = tradeBotData.getLastTransactionSignature();

		// Skip past previously processed messages
		if (originalLastTransactionSignature != null)
			for (int i = 0; i < messageTransactionsData.size(); ++i)
				if (Arrays.equals(messageTransactionsData.get(i).getSignature(), originalLastTransactionSignature)) {
					messageTransactionsData.subList(0, i + 1).clear();
					break;
				}

		while (!messageTransactionsData.isEmpty()) {
			MessageTransactionData messageTransactionData = messageTransactionsData.remove(0);
			tradeBotData.setLastTransactionSignature(messageTransactionData.getSignature());

			if (messageTransactionData.isText())
				continue;

			// We're expecting: HASH160(secret-A), Alice's Bitcoin pubkeyhash and lockTime-A
			byte[] messageData = messageTransactionData.getData();
			BTCACCT.OfferMessageData offerMessageData = BTCACCT.extractOfferMessageData(messageData);
			if (offerMessageData == null)
				continue;

			byte[] aliceForeignPublicKeyHash = offerMessageData.recipientBitcoinPKH;
			byte[] hashOfSecretA = offerMessageData.hashOfSecretA;
			int lockTimeA = (int) offerMessageData.lockTimeA;
			// Determine P2SH-A address and confirm funded
			byte[] redeemScript = BTCP2SH.buildScript(aliceForeignPublicKeyHash, lockTimeA, tradeBotData.getTradeForeignPublicKeyHash(), hashOfSecretA);
			String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScript);

			Long balance = BTC.getInstance().getBalance(p2shAddress);
			if (balance == null || balance < tradeBotData.getBitcoinAmount())
				continue;

			// Good to go - send MESSAGE to AT

			String aliceNativeAddress = Crypto.toAddress(messageTransactionData.getCreatorPublicKey());
			int lockTimeB = BTCACCT.calcLockTimeB(messageTransactionData.getTimestamp(), lockTimeA);

			// Build outgoing message, padding each part to 32 bytes to make it easier for AT to consume
			byte[] outgoingMessageData = BTCACCT.buildTradeMessage(aliceNativeAddress, aliceForeignPublicKeyHash, hashOfSecretA, lockTimeA, lockTimeB);

			PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
			MessageTransaction outgoingMessageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, tradeBotData.getAtAddress(), outgoingMessageData, false, false);

			outgoingMessageTransaction.computeNonce();
			outgoingMessageTransaction.sign(sender);

			// reset repository state to prevent deadlock
			repository.discardChanges();
			ValidationResult result = outgoingMessageTransaction.importAsUnconfirmed();

			if (result != ValidationResult.OK) {
				LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: %s", outgoingMessageTransaction.getRecipient(), result.name()));
				return;
			}

			tradeBotData.setState(TradeBotData.State.BOB_WAITING_FOR_P2SH_B);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();

			byte[] redeemScriptBytes = BTCP2SH.buildScript(aliceForeignPublicKeyHash, lockTimeB, tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getHashOfSecret());
			String p2shBAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

			LOGGER.info(() -> String.format("Locked AT %s to %s. Waiting for P2SH-B %s", tradeBotData.getAtAddress(), aliceNativeAddress, p2shBAddress));

			return;
		}

		// Don't resave if we don't need to
		if (tradeBotData.getLastTransactionSignature() != originalLastTransactionSignature) {
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();
		}
	}

	private void handleAliceWaitingForAtLock(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// Refund P2SH-A if AT finished (i.e. Bob cancelled trade) or we've passed lockTime-A
		if (atData.getIsFinished() || NTP.getTime() >= tradeBotData.getLockTimeA()) {
			tradeBotData.setState(TradeBotData.State.ALICE_REFUNDING_A);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();

			byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
			String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

			if (atData.getIsFinished())
				LOGGER.info(() -> String.format("AT %s cancelled. Refunding P2SH-A %s - aborting trade", tradeBotData.getAtAddress(), p2shAddress));
			else
				LOGGER.info(() -> String.format("LockTime-A reached, refunding P2SH-A %s - aborting trade", p2shAddress));

			return;
		}

		// We're waiting for AT to be in TRADE mode
		if (crossChainTradeData.mode != CrossChainTradeData.Mode.TRADE)
			return;

		// We're expecting AT to be locked to our native trade address
		if (!crossChainTradeData.qortalRecipient.equals(tradeBotData.getTradeNativeAddress())) {
			// AT locked to different address! We shouldn't continue but wait and refund.

			byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
			String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

			LOGGER.warn(() -> String.format("AT %s locked to %s, not us (%s). Refunding %s - aborting trade",
					tradeBotData.getAtAddress(),
					crossChainTradeData.qortalRecipient,
					tradeBotData.getTradeNativeAddress(),
					p2shAddress));

			// There's no P2SH-B at this point, so jump straight to refunding P2SH-A
			tradeBotData.setState(TradeBotData.State.ALICE_REFUNDING_A);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();

			return;
		}

		// Alice needs to fund P2SH-B here

		// Find our MESSAGE to AT from previous state
		List<MessageTransactionData> messageTransactionsData = repository.getTransactionRepository().getMessagesByRecipient(crossChainTradeData.qortalCreatorTradeAddress, null, null, null);
		if (messageTransactionsData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch messages to trade AT %s from repository", crossChainTradeData.qortalCreatorTradeAddress));
			return;
		}

		// Find our message
		Long recipientMessageTimestamp = null;
		for (MessageTransactionData messageTransactionData : messageTransactionsData)
			if (Arrays.equals(messageTransactionData.getSenderPublicKey(), tradeBotData.getTradeNativePublicKey())) {
				recipientMessageTimestamp = messageTransactionData.getTimestamp();
				break;
			}

		if (recipientMessageTimestamp == null) {
			LOGGER.warn(() -> String.format("Unable to find our message to trade creator %s?", crossChainTradeData.qortalCreatorTradeAddress));
			return;
		}

		int lockTimeA = tradeBotData.getLockTimeA();
		int lockTimeB = BTCACCT.calcLockTimeB(recipientMessageTimestamp, lockTimeA);

		// Our calculated lockTime-B should match AT's calculated lockTime-B
		if (lockTimeB != crossChainTradeData.lockTimeB) {
			LOGGER.debug(() -> String.format("Trade AT lockTime-B '%d' doesn't match our lockTime-B '%d'", crossChainTradeData.lockTimeB, lockTimeB));
			// We'll eventually refund
			return;
		}

		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		Transaction p2shFundingTransaction = BTC.getInstance().buildSpend(tradeBotData.getXprv58(), p2shAddress, FEE_AMOUNT);
		if (!BTC.getInstance().broadcastTransaction(p2shFundingTransaction)) {
			// We couldn't fund P2SH-B at this time
			LOGGER.debug(() -> String.format("Couldn't broadcast P2SH-B funding transaction?"));
			return;
		}

		// P2SH-B funded, now we wait for Bob to redeem it
		tradeBotData.setState(TradeBotData.State.ALICE_WATCH_P2SH_B);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		LOGGER.info(() -> String.format("AT %s locked to us (%s). P2SH-B %s funded. Watching P2SH-B for secret-B",
				tradeBotData.getAtAddress(), tradeBotData.getTradeNativeAddress(), p2shAddress));
	}

	private void handleBobWaitingForP2shB(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// If we've passed AT refund timestamp then AT will have finished after auto-refunding
		if (atData.getIsFinished()) {
			tradeBotData.setState(TradeBotData.State.BOB_REFUNDED);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();

			LOGGER.info(() -> String.format("AT %s has auto-refunded - trade aborted", tradeBotData.getAtAddress()));

			return;
		}

		// It's possible AT hasn't processed our previous MESSAGE yet and so lockTimeB won't be set
		if (crossChainTradeData.lockTimeB == null)
			// AT yet to process MESSAGE
			return;

		byte[] redeemScriptBytes = BTCP2SH.buildScript(crossChainTradeData.recipientBitcoinPKH, crossChainTradeData.lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		Long balance = BTC.getInstance().getBalance(p2shAddress);
		if (balance == null || balance < FEE_AMOUNT) {
			if (balance != null && balance > 0)
				LOGGER.debug(() -> String.format("P2SH-B balance %s lower than expected %s", BTC.format(balance), BTC.format(FEE_AMOUNT)));

			return;
		}

		// Redeem P2SH-B using secret-B
		Coin redeemAmount = Coin.ZERO; // The real funds are in P2SH-A
		ECKey redeemKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
		List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress);

		Transaction p2shRedeemTransaction = BTCP2SH.buildRedeemTransaction(redeemAmount, redeemKey, fundingOutputs, redeemScriptBytes, tradeBotData.getSecret());

		if (!BTC.getInstance().broadcastTransaction(p2shRedeemTransaction)) {
			// We couldn't redeem P2SH-B at this time
			LOGGER.debug(() -> String.format("Couldn't broadcast P2SH-B redeeming transaction?"));
			return;
		}

		// P2SH-B redeemed, now we wait for Alice to use secret-A to redeem AT
		tradeBotData.setState(TradeBotData.State.BOB_WAITING_FOR_AT_REDEEM);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		LOGGER.info(() -> String.format("P2SH-B %s redeemed (exposing secret-B). Watching AT %s for secret-A", p2shAddress, tradeBotData.getAtAddress()));
	}

	private void handleAliceWatchingP2shB(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), crossChainTradeData.lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		// Refund P2SH-B if we've passed lockTime-B
		if (NTP.getTime() >= crossChainTradeData.lockTimeB) {
			tradeBotData.setState(TradeBotData.State.ALICE_REFUNDING_B);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();

			LOGGER.info(() -> String.format("LockTime-B reached, refunding P2SH-B %s - aborting trade", p2shAddress));

			return;
		}

		List<byte[]> p2shTransactions = BTC.getInstance().getAddressTransactions(p2shAddress);
		if (p2shTransactions == null) {
			LOGGER.debug(() -> String.format("Unable to fetch transactions relating to %s", p2shAddress));
			return;
		}

		byte[] secretB = BTCP2SH.findP2shSecret(p2shAddress, p2shTransactions);
		if (secretB == null)
			// Secret not revealed at this time
			return;

		// Send MESSAGE to AT using both secrets
		byte[] secretA = tradeBotData.getSecret();
		byte[] messageData = BTCACCT.buildRedeemMessage(secretA, secretB);

		PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
		MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, tradeBotData.getAtAddress(), messageData, false, false);

		messageTransaction.computeNonce();
		messageTransaction.sign(sender);

		// Reset repository state to prevent deadlock
		repository.discardChanges();
		ValidationResult result = messageTransaction.importAsUnconfirmed();

		if (result != ValidationResult.OK) {
			LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: %s", messageTransaction.getRecipient(), result.name()));
			return;
		}

		tradeBotData.setState(TradeBotData.State.ALICE_DONE);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		String receiveAddress = tradeBotData.getTradeNativeAddress();

		LOGGER.info(() -> String.format("P2SH-B %s redeemed, using secrets to redeem AT %s. Funds should arrive at %s",
				p2shAddress, tradeBotData.getAtAddress(), receiveAddress));
	}

	private void handleBobWaitingForAtRedeem(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// AT should be 'finished' once Alice has redeemed QORT funds
		if (!atData.getIsFinished())
			// Not finished yet
			return;

		// If AT's balance is zero, then it's auto-refunded so we're done
		AccountBalanceData atBalanceData = repository.getAccountRepository().getBalance(tradeBotData.getAtAddress(), Asset.QORT);
		if (atBalanceData == null || atBalanceData.getBalance() == 0L) {
			tradeBotData.setState(TradeBotData.State.BOB_REFUNDED);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();

			LOGGER.info(() -> String.format("AT %s has auto-refunded - trade aborted", tradeBotData.getAtAddress()));

			return;
		}

		byte[] secretA = BTCACCT.findSecretA(repository, crossChainTradeData);
		if (secretA == null) {
			LOGGER.debug(() -> String.format("Unable to find secret-A from redeem message to AT %s?", tradeBotData.getAtAddress()));
			return;
		}

		// Use secret-A to redeem P2SH-A

		byte[] redeemScriptBytes = BTCP2SH.buildScript(crossChainTradeData.recipientBitcoinPKH, crossChainTradeData.lockTimeA, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretA);
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		Coin redeemAmount = Coin.valueOf(crossChainTradeData.expectedBitcoin);
		ECKey redeemKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
		List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress);

		Transaction p2shRedeemTransaction = BTCP2SH.buildRedeemTransaction(redeemAmount, redeemKey, fundingOutputs, redeemScriptBytes, secretA);

		if (!BTC.getInstance().broadcastTransaction(p2shRedeemTransaction)) {
			// We couldn't redeem P2SH-A at this time
			LOGGER.debug(() -> String.format("Couldn't broadcast P2SH-A redeeming transaction?"));
			return;
		}

		tradeBotData.setState(TradeBotData.State.BOB_DONE);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		String receiveAddress = BTC.getInstance().pkhToAddress(tradeBotData.getTradeForeignPublicKeyHash());

		LOGGER.info(() -> String.format("P2SH-A %s redeemed. Funds should arrive at %s", tradeBotData.getAtAddress(), receiveAddress));
	}

	private void handleAliceRefundingP2shB(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// We can't refund P2SH-B until lockTime-B has passed
		if (NTP.getTime() <= crossChainTradeData.lockTimeB)
			return;

		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), crossChainTradeData.lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		Coin refundAmount = Coin.ZERO;
		ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
		List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress);

		Transaction p2shRefundTransaction = BTCP2SH.buildRefundTransaction(refundAmount, refundKey, fundingOutputs, redeemScriptBytes, tradeBotData.getLockTimeA());
		if (!BTC.getInstance().broadcastTransaction(p2shRefundTransaction)) {
			// We couldn't refund P2SH-B at this time
			LOGGER.debug(() -> String.format("Couldn't broadcast P2SH-B refund transaction?"));
			return;
		}

		tradeBotData.setState(TradeBotData.State.ALICE_REFUNDING_A);

		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		LOGGER.info(() -> String.format("Refunded P2SH-B %s. Waiting for LockTime-A", p2shAddress));
	}

	private void handleAliceRefundingP2shA(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// We can't refund P2SH-A until lockTime-A has passed
		if (NTP.getTime() <= tradeBotData.getLockTimeA())
			return;

		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		Coin refundAmount = Coin.valueOf(crossChainTradeData.expectedBitcoin);
		ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
		List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress);

		Transaction p2shRefundTransaction = BTCP2SH.buildRefundTransaction(refundAmount, refundKey, fundingOutputs, redeemScriptBytes, tradeBotData.getLockTimeA());
		if (!BTC.getInstance().broadcastTransaction(p2shRefundTransaction)) {
			// We couldn't refund P2SH-A at this time
			LOGGER.debug(() -> String.format("Couldn't broadcast P2SH-A refund transaction?"));
			return;
		}

		tradeBotData.setState(TradeBotData.State.ALICE_REFUNDED);

		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		LOGGER.info(() -> String.format("LockTime-A reached. Refunded P2SH-A %s. Trade aborted", p2shAddress));
	}

}
