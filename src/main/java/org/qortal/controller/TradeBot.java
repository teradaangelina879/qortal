package org.qortal.controller;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.ECKey;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.model.TradeBotCreateRequest;
import org.qortal.asset.Asset;
import org.qortal.crosschain.BTC;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crosschain.BTCP2SH;
import org.qortal.crypto.Crypto;
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
		String tradeAddress = Crypto.toAddress(tradeNativePublicKey);

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
		byte[] creationBytes = BTCACCT.buildQortalAT(tradeAddress, tradeForeignPublicKeyHash, hashOfSecretB, tradeBotCreateRequest.qortAmount, tradeBotCreateRequest.bitcoinAmount, tradeBotCreateRequest.tradeTimeout);
		long amount = tradeBotCreateRequest.fundingQortAmount;

		DeployAtTransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, aTType, tags, creationBytes, amount, Asset.QORT);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		fee = deployAtTransaction.calcRecommendedFee();
		deployAtTransactionData.setFee(fee);

		DeployAtTransaction.ensureATAddress(deployAtTransactionData);
		String atAddress = deployAtTransactionData.getAtAddress();

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, TradeBotData.State.BOB_WAITING_FOR_AT_CONFIRM,
				atAddress,
				tradeNativePublicKey, tradeNativePublicKeyHash, secretB, hashOfSecretB,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.bitcoinAmount, null, null);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		// Return to user for signing and broadcast as we don't have their Qortal private key
		try {
			return DeployAtTransactionTransformer.toBytes(deployAtTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform DEPLOY_AT transaction?", e);
		}
	}

	public static String startResponse(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException {
		byte[] tradePrivateKey = generateTradePrivateKey();
		byte[] secretA = generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);

		byte[] tradeNativePublicKey = deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		// We need to generate lockTimeA: halfway of refundTimeout from now
		int lockTimeA = crossChainTradeData.tradeTimeout * 60 + (int) (NTP.getTime() / 1000L);

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, TradeBotData.State.ALICE_WAITING_FOR_P2SH_A,
				crossChainTradeData.qortalAtAddress,
				tradeNativePublicKey, tradeNativePublicKeyHash, secretA, hashOfSecretA,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				crossChainTradeData.expectedBitcoin, null, lockTimeA);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		// P2SH_a to be funded
		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeForeignPublicKeyHash, lockTimeA, crossChainTradeData.creatorBitcoinPKH, hashOfSecretA);
		return BTC.getInstance().deriveP2shAddress(redeemScriptBytes);
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

					case BOB_WAITING_FOR_MESSAGE:
						handleBobWaitingForMessage(repository, tradeBotData);
						break;

					case ALICE_WAITING_FOR_P2SH_A:
						handleAliceWaitingForP2shA(repository, tradeBotData);
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
	}

	private void handleBobWaitingForMessage(Repository repository, TradeBotData tradeBotData) throws DataException {
		// Fetch AT so we can determine trade start timestamp
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT '%s' from repository", tradeBotData.getAtAddress()));
			return;
		}

		String address = Crypto.toAddress(tradeBotData.getTradeNativePublicKey());
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

			// Could enforce encryption here

			// We're expecting: HASH160(secret) + Alice's Bitcoin pubkeyhash
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
				LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT '%s': %s", outgoingMessageTransaction.getRecipient(), result.name()));
				return;
			}

			tradeBotData.setState(TradeBotData.State.BOB_WAITING_FOR_P2SH_B);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();
			return;
		}

		// Don't resave if we don't need to
		if (tradeBotData.getLastTransactionSignature() != originalLastTransactionSignature) {
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();
		}
	}

	private void handleAliceWaitingForP2shA(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT '%s' from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		Long balance = BTC.getInstance().getBalance(p2shAddress);
		if (balance == null || balance < crossChainTradeData.expectedBitcoin)
			return;

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
			LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT '%s': %s", messageTransaction.getRecipient(), result.name()));
			return;
		}

		tradeBotData.setState(TradeBotData.State.ALICE_WAITING_FOR_AT_LOCK);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();
	}

}
