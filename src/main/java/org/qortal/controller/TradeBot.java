package org.qortal.controller;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script.ScriptType;
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
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;
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

	/**
	 * Creates a new trade-bot entry from the "Bob" viewpoint, i.e. OFFERing QORT in exchange for BTC.
	 * <p>
	 * Generates:
	 * <ul>
	 * 	<li>new 'trade' private key</li>
	 * 	<li>secret-B</li>
	 * </ul>
	 * Derives:
	 * <ul>
	 * 	<li>'native' (as in Qortal) public key, public key hash, address (starting with Q)</li>
	 * 	<li>'foreign' (as in Bitcoin) public key, public key hash</li>
	 *	<li>HASH160 of secret-B</li>
	 * </ul>
	 * A Qortal AT is then constructed including the following as constants in the 'data segment':
	 * <ul>
	 * 	<li>'native'/Qortal 'trade' address - used as a MESSAGE contact</li>
	 * 	<li>'foreign'/Bitcoin public key hash - used by Alice's P2SH scripts to allow redeem</li>
	 * 	<li>HASH160 of secret-B - used by AT and P2SH to validate a potential secret-B</li>
	 * 	<li>QORT amount on offer by Bob</li>
	 * 	<li>BTC amount expected in return by Bob (from Alice)</li>
	 * 	<li>trading timeout, in case things go wrong and everyone needs to refund</li>
	 * </ul>
	 * Returns a DEPLOY_AT transaction that needs to be signed and broadcast to the Qortal network.
	 * <p>
	 * Trade-bot will wait for Bob's AT to be deployed before taking next step.
	 * <p>
	 * @param repository
	 * @param tradeBotCreateRequest
	 * @return raw, unsigned DEPLOY_AT transaction
	 * @throws DataException
	 */
	public static byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) throws DataException {
		byte[] tradePrivateKey = generateTradePrivateKey();
		byte[] secretB = generateSecret();
		byte[] hashOfSecretB = Crypto.hash160(secretB);

		byte[] tradeNativePublicKey = deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
		String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		// Convert Bitcoin receive address into public key hash (we only support P2PKH at this time)
		Address bitcoinReceiveAddress;
		try {
			bitcoinReceiveAddress = Address.fromString(BTC.getInstance().getNetworkParameters(), tradeBotCreateRequest.receiveAddress);
		} catch (AddressFormatException e) {
			throw new DataException("Unsupported Bitcoin receive address: " + tradeBotCreateRequest.receiveAddress);
		}
		if (bitcoinReceiveAddress.getOutputScriptType() != ScriptType.P2PKH)
			throw new DataException("Unsupported Bitcoin receive address: " + tradeBotCreateRequest.receiveAddress);

		byte[] bitcoinReceivePublicKeyHash = bitcoinReceiveAddress.getHash();

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
		byte[] creationBytes = BTCACCT.buildQortalAT(tradeNativeAddress, tradeForeignPublicKeyHash, hashOfSecretB, tradeBotCreateRequest.qortAmount,
				tradeBotCreateRequest.bitcoinAmount, tradeBotCreateRequest.tradeTimeout);
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
				tradeBotCreateRequest.bitcoinAmount, null, null, null, bitcoinReceivePublicKeyHash);
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

	/**
	 * Creates a trade-bot entry from the 'Alice' viewpoint, i.e. matching BTC to an existing offer.
	 * <p>
	 * Requires a chosen trade offer from Bob, passed by <tt>crossChainTradeData</tt>
	 * and access to a Bitcoin wallet via <tt>xprv58</tt>.
	 * <p>
	 * The <tt>crossChainTradeData</tt> contains the current trade offer state
	 * as extracted from the AT's data segment.
	 * <p>
	 * Access to a funded wallet is via a Bitcoin BIP32 hierarchical deterministic key,
	 * passed via <tt>xprv58</tt>.
	 * <b>This key will be stored in your node's database</b>
	 * to allow trade-bot to create/fund the necessary P2SH transactions!
	 * However, due to the nature of BIP32 keys, it is possible to give the trade-bot
	 * only a subset of wallet access (see BIP32 for more details).
	 * <p>
	 * As an example, the xprv58 can be extract from a <i>legacy, password-less</i>
	 * Electrum wallet by going to the console tab and entering:<br>
	 * <tt>wallet.keystore.xprv</tt><br>
	 * which should result in a base58 string starting with either 'xprv' (for Bitcoin main-net)
	 * or 'tprv' for (Bitcoin test-net).
	 * <p>
	 * It is envisaged that the value in <tt>xprv58</tt> will actually come from a Qortal-UI-managed wallet.
	 * <p>
	 * If sufficient funds are available, <b>this method will actually fund the P2SH-A</b>
	 * with the Bitcoin amount expected by 'Bob'.
	 * <p>
	 * If the Bitcoin transaction is successfully broadcast to the network then the trade-bot entry
	 * is saved to the repository and the cross-chain trading process commences.
	 * <p>
	 * Trade-bot will wait for P2SH-A to confirm before taking next step.
	 * <p>
	 * @param repository
	 * @param crossChainTradeData chosen trade OFFER that Alice wants to match
	 * @param xprv58 funded wallet xprv in base58
	 * @return true if P2SH-A funding transaction successfully broadcast to Bitcoin network, false otherwise
	 * @throws DataException
	 */
	public static boolean startResponse(Repository repository, CrossChainTradeData crossChainTradeData, String xprv58, String receivingAddress) throws DataException {
		byte[] tradePrivateKey = generateTradePrivateKey();
		byte[] secretA = generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);

		byte[] tradeNativePublicKey = deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
		String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
		byte[] receivingPublicKeyHash = Base58.decode(receivingAddress); // Actually the whole address, not just PKH

		// We need to generate lockTime-A: halfway of refundTimeout from now
		int lockTimeA = crossChainTradeData.tradeTimeout * 60 + (int) (NTP.getTime() / 1000L);

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, TradeBotData.State.ALICE_WAITING_FOR_P2SH_A,
				crossChainTradeData.qortalAtAddress,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				secretA, hashOfSecretA,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				crossChainTradeData.expectedBitcoin, xprv58, null, lockTimeA, receivingPublicKeyHash);

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
		// The private key is used for both Curve25519 and secp256k1 so needs to be valid for both.
		// Curve25519 accepts any seed, so generate a valid secp256k1 key and use that.
		return new ECKey().getPrivKeyBytes();
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

	/**
	 * Trade-bot is waiting for Bob's AT to deploy.
	 * <p>
	 * If AT is deployed, then trade-bot's next step is to wait for MESSAGE from Alice.
	 */
	private void handleBobWaitingForAtConfirm(Repository repository, TradeBotData tradeBotData) throws DataException {
		if (!repository.getATRepository().exists(tradeBotData.getAtAddress()))
			return;

		tradeBotData.setState(TradeBotData.State.BOB_WAITING_FOR_MESSAGE);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		LOGGER.info(() -> String.format("AT %s confirmed ready. Waiting for trade message", tradeBotData.getAtAddress()));
	}

	/**
	 * Trade-bot is waiting for Alice's P2SH-A to confirm.
	 * <p>
	 * If P2SH-A is confirmed, then trade-bot's next step is to MESSAGE Bob's trade address with Alice's trade info.
	 * <p>
	 * It is possible between broadcast and confirmation of P2SH-A funding transaction, that Bob has cancelled his trade offer.
	 * If this is detected then trade-bot's next step is to wait until P2SH-A can refund back to Alice.
	 * <p>
	 * In normal operation, trade-bot send a zero-fee, PoW MESSAGE on Alice's behalf containing:
	 * <ul>
	 * 	<li>Alice's 'foreign'/Bitcoin public key hash - so Bob's trade-bot can derive P2SH-A address and check balance</li>
	 * 	<li>HASH160 of Alice's secret-A - also used to derive P2SH-A address</li>
	 * 	<li>lockTime of P2SH-A - also used to derive P2SH-A address, but also for other use later in the trading process</li>
	 * </ul>
	 * If MESSAGE transaction is successfully broadcast, trade-bot's next step is to wait until Bob's AT has locked trade to Alice only.
	 */
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

	/**
	 * Trade-bot is waiting for MESSAGE from Alice's trade-bot, containing Alice's trade info.
	 * <p>
	 * It's possible Bob has cancelling his trade offer, receiving an automatic QORT refund,
	 * in which case trade-bot is done with this specific trade and finalizes on refunded state.
	 * <p>
	 * Assuming trade is still on offer, trade-bot checks the contents of MESSAGE from Alice's trade-bot.
	 * <p>
	 * Details from Alice are used to derive P2SH-A address and this is checked for funding balance.
	 * <p>
	 * Assuming P2SH-A has at least expected Bitcoin balance,
	 * Bob's trade-bot constructs a zero-fee, PoW MESSAGE to send to Bob's AT with more trade details.
	 * <p>
	 * On processing this MESSAGE, Bob's AT should switch into 'TRADE' mode and only trade with Alice.
	 * <p>
	 * Trade-bot's next step is to wait for P2SH-B, which will allow Bob to reveal his secret-B,
	 * needed by Alice to progress her side of the trade.
	 */
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

			byte[] aliceForeignPublicKeyHash = offerMessageData.partnerBitcoinPKH;
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

	/**
	 * Trade-bot is waiting for Bob's AT to switch to TRADE mode and lock trade to Alice only.
	 * <p>
	 * It's possible that Bob has cancelled his trade offer in the mean time, or that somehow
	 * this process has taken so long that we've reached P2SH-A's locktime, or that someone else
	 * has managed to trade with Bob. In any of these cases, trade-bot switches to begin the refunding process.
	 * <p>
	 * Assuming Bob's AT is locked to Alice, trade-bot checks AT's state data to make sure it is correct.
	 * <p>
	 * If all is well, trade-bot then uses Bitcoin wallet to (token) fund P2SH-B.
	 * <p>
	 * If P2SH-B funding transaction is successfully broadcast to the Bitcoin network, trade-bot's next
	 * step is to watch for Bob revealing secret-B by redeeming P2SH-B.
	 */
	private void handleAliceWaitingForAtLock(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// Refund P2SH-A if AT finished (i.e. Bob cancelled trade) or we've passed lockTime-A
		if (atData.getIsFinished() || NTP.getTime() >= tradeBotData.getLockTimeA() * 1000L) {
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
		if (crossChainTradeData.mode != BTCACCT.Mode.TRADING)
			return;

		// We're expecting AT to be locked to our native trade address
		if (!crossChainTradeData.qortalPartnerAddress.equals(tradeBotData.getTradeNativeAddress())) {
			// AT locked to different address! We shouldn't continue but wait and refund.

			byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
			String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

			LOGGER.warn(() -> String.format("AT %s locked to %s, not us (%s). Refunding %s - aborting trade",
					tradeBotData.getAtAddress(),
					crossChainTradeData.qortalPartnerAddress,
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

	/**
	 * Trade-bot is waiting for P2SH-B to funded.
	 * <p>
	 * It's possible than Bob's AT has reached it's trading timeout and automatically refunded QORT back to Bob.
	 * In which case, trade-bot is done with this specific trade and finalizes on refunded state.
	 * <p>
	 * Assuming P2SH-B is funded, trade-bot 'redeems' this P2SH using secret-B, thus revealing it to Alice.
	 * <p>
	 * Trade-bot's next step is to wait for Alice to use secret-B, and her secret-A, to redeem Bob's AT.
	 */
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

		byte[] redeemScriptBytes = BTCP2SH.buildScript(crossChainTradeData.partnerBitcoinPKH, crossChainTradeData.lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
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
		byte[] receivePublicKeyHash = tradeBotData.getReceivingPublicKeyHash();

		Transaction p2shRedeemTransaction = BTCP2SH.buildRedeemTransaction(redeemAmount, redeemKey, fundingOutputs, redeemScriptBytes, tradeBotData.getSecret(), receivePublicKeyHash);

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

	/**
	 * Trade-bot is waiting for Bob to redeem P2SH-B thus revealing secret-B to Alice.
	 * <p>
	 * It's possible that this process has taken so long that we've reached P2SH-B's locktime.
	 * In which case, trade-bot switches to begin the refund process.
	 * <p>
	 * If trade-bot can extract a valid secret-B from the spend of P2SH-B, then it creates a
	 * zero-fee, PoW MESSAGE to send to Bob's AT, including both secret-B and also Alice's secret-A.
	 * <p>
	 * Both secrets are needed to release the QORT funds from Bob's AT to Alice's 'native'/Qortal
	 * trade address.
	 * <p>
	 * In revealing a valid secret-A, Bob can then redeem the BTC funds from P2SH-A.
	 * <p>
	 * If trade-bot successfully broadcasts the MESSAGE transaction, then this specific trade is done.
	 */
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
		if (NTP.getTime() >= crossChainTradeData.lockTimeB * 1000L) {
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

		// Send 'redeem' MESSAGE to AT using both secrets
		byte[] secretA = tradeBotData.getSecret();
		String qortalReceiveAddress = Base58.encode(tradeBotData.getReceivingPublicKeyHash()); // Actually contains whole address, not just PKH
		byte[] messageData = BTCACCT.buildRedeemMessage(secretA, secretB, qortalReceiveAddress);

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

	/**
	 * Trade-bot is waiting for Alice to redeem Bob's AT, thus revealing secret-A which is required to spend the BTC funds from P2SH-A.
	 * <p>
	 * It's possible that Bob's AT has reached its trading timeout and automatically refunded QORT back to Bob. In which case,
	 * trade-bot is done with this specific trade and finalizes in refunded state.
	 * <p>
	 * Assuming trade-bot can extract a valid secret-A from Alice's MESSAGE then trade-bot uses that to redeem the BTC funds from P2SH-A
	 * to Bob's 'foreign'/Bitcoin trade legacy-format address, as derived from trade private key.
	 * <p>
	 * (This could potentially be 'improved' to send BTC to any address of Bob's choosing by changing the transaction output).
	 * <p>
	 * If trade-bot successfully broadcasts the transaction, then this specific trade is done.
	 */
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

		// If AT's balance should be zero
		AccountBalanceData atBalanceData = repository.getAccountRepository().getBalance(tradeBotData.getAtAddress(), Asset.QORT);
		if (atBalanceData != null && atBalanceData.getBalance() > 0L) {
			LOGGER.debug(() -> String.format("AT %s should have zero balance, not %s", tradeBotData.getAtAddress(), Amounts.prettyAmount(atBalanceData.getBalance())));
			return;
		}

		// We check variable in AT that is set when trade successfully completes
		if (crossChainTradeData.mode != BTCACCT.Mode.REDEEMED) {
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

		byte[] redeemScriptBytes = BTCP2SH.buildScript(crossChainTradeData.partnerBitcoinPKH, crossChainTradeData.lockTimeA, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretA);
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		Coin redeemAmount = Coin.valueOf(crossChainTradeData.expectedBitcoin);
		ECKey redeemKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
		List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress);
		byte[] receivePublicKeyHash = tradeBotData.getReceivingPublicKeyHash();

		Transaction p2shRedeemTransaction = BTCP2SH.buildRedeemTransaction(redeemAmount, redeemKey, fundingOutputs, redeemScriptBytes, secretA, receivePublicKeyHash);

		if (!BTC.getInstance().broadcastTransaction(p2shRedeemTransaction)) {
			// We couldn't redeem P2SH-A at this time
			LOGGER.debug(() -> String.format("Couldn't broadcast P2SH-A redeeming transaction?"));
			return;
		}

		tradeBotData.setState(TradeBotData.State.BOB_DONE);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		String receiveAddress = BTC.getInstance().pkhToAddress(receivePublicKeyHash);

		LOGGER.info(() -> String.format("P2SH-A %s redeemed. Funds should arrive at %s", tradeBotData.getAtAddress(), receiveAddress));
	}

	/**
	 * Trade-bot is attempting to refund P2SH-B.
	 * <p>
	 * We could potentially skip this step as P2SH-B is only funded with a token amount to cover the mining fee should Bob redeem P2SH-B.
	 * <p>
	 * Upon successful broadcast of P2SH-B refunding transaction, trade-bot's next step is to begin refunding of P2SH-A.
	 */
	private void handleAliceRefundingP2shB(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// We can't refund P2SH-B until lockTime-B has passed
		if (NTP.getTime() <= crossChainTradeData.lockTimeB * 1000L)
			return;

		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), crossChainTradeData.lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		Coin refundAmount = Coin.ZERO;
		ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
		List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress);

		Transaction p2shRefundTransaction = BTCP2SH.buildRefundTransaction(refundAmount, refundKey, fundingOutputs, redeemScriptBytes, crossChainTradeData.lockTimeB);
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

	/** Trade-bot is attempting to refund P2SH-A. */
	private void handleAliceRefundingP2shA(Repository repository, TradeBotData tradeBotData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// We can't refund P2SH-A until lockTime-A has passed
		if (NTP.getTime() <= tradeBotData.getLockTimeA() * 1000L)
			return;

		// We can't refund P2SH-A until we've passed median block time
		Integer medianBlockTime = BTC.getInstance().getMedianBlockTime();
		if (medianBlockTime == null || NTP.getTime() <= medianBlockTime * 1000L)
			return;

		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		Coin refundAmount = Coin.valueOf(crossChainTradeData.expectedBitcoin);
		ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
		List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress);
		if (fundingOutputs == null) {
			LOGGER.debug(() -> String.format("Couldn't fetch unspent outputs for %s", p2shAddress));
			return;
		}

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
