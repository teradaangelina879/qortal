package org.qortal.controller;

import java.awt.TrayIcon.MessageType;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Supplier;
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
import org.qortal.crosschain.BitcoinException;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;
import org.qortal.group.Group;
import org.qortal.gui.SysTray;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.DeployAtTransactionTransformer;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

/**
 * Performing cross-chain trading steps on behalf of user.
 * <p>
 * We deal with three different independent state-spaces here:
 * <ul>
 * 	<li>Qortal blockchain</li>
 * 	<li>Bitcoin blockchain</li>
 * 	<li>Trade-bot entries</li>
 * </ul>
 */
public class TradeBot implements Listener {

	public enum ResponseResult { OK, INSUFFICIENT_FUNDS, BTC_BALANCE_ISSUE, BTC_NETWORK_ISSUE }

	public static class StateChangeEvent implements Event {
		private final TradeBotData tradeBotData;

		public StateChangeEvent(TradeBotData tradeBotData) {
			this.tradeBotData = tradeBotData;
		}

		public TradeBotData getTradeBotData() {
			return this.tradeBotData;
		}
	}

	private static final Logger LOGGER = LogManager.getLogger(TradeBot.class);
	private static final Random RANDOM = new SecureRandom();

	/** Maximum time Bob waits for his AT creation transaction to be confirmed into a block. */
	private static final long MAX_AT_CONFIRMATION_PERIOD = 24 * 60 * 60 * 1000L; // ms

	private static final long P2SH_B_OUTPUT_AMOUNT = 1000L; // P2SH-B output amount needs to be higher than the dust threshold (3000 sats/kB).

	private static TradeBot instance;

	private TradeBot() {
		EventBus.INSTANCE.addListener(event -> TradeBot.getInstance().listen(event));
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

		// Convert Bitcoin receiving address into public key hash (we only support P2PKH at this time)
		Address bitcoinReceivingAddress;
		try {
			bitcoinReceivingAddress = Address.fromString(BTC.getInstance().getNetworkParameters(), tradeBotCreateRequest.receivingAddress);
		} catch (AddressFormatException e) {
			throw new DataException("Unsupported Bitcoin receiving address: " + tradeBotCreateRequest.receivingAddress);
		}
		if (bitcoinReceivingAddress.getOutputScriptType() != ScriptType.P2PKH)
			throw new DataException("Unsupported Bitcoin receiving address: " + tradeBotCreateRequest.receivingAddress);

		byte[] bitcoinReceivingAccountInfo = bitcoinReceivingAddress.getHash();

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
				creator.getAddress(), atAddress, timestamp, tradeBotCreateRequest.qortAmount,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				secretB, hashOfSecretB,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.bitcoinAmount, null, null, null, bitcoinReceivingAccountInfo);

		updateTradeBotState(repository, tradeBotData, tradeBotData.getState(),
				() -> String.format("Built AT %s. Waiting for deployment", atAddress));

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
	public static ResponseResult startResponse(Repository repository, CrossChainTradeData crossChainTradeData, String xprv58, String receivingAddress) throws DataException {
		byte[] tradePrivateKey = generateTradePrivateKey();
		byte[] secretA = generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);

		byte[] tradeNativePublicKey = deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
		String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
		byte[] receivingPublicKeyHash = Base58.decode(receivingAddress); // Actually the whole address, not just PKH

		// We need to generate lockTime-A: add tradeTimeout to now
		int lockTimeA = crossChainTradeData.tradeTimeout * 60 + (int) (NTP.getTime() / 1000L);

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, TradeBotData.State.ALICE_WAITING_FOR_P2SH_A,
				receivingAddress, crossChainTradeData.qortalAtAddress, NTP.getTime(), crossChainTradeData.qortAmount,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				secretA, hashOfSecretA,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				crossChainTradeData.expectedBitcoin, xprv58, null, lockTimeA, receivingPublicKeyHash);

		// Check we have enough funds via xprv58 to fund both P2SHs to cover expectedBitcoin
		String tradeForeignAddress = BTC.getInstance().pkhToAddress(tradeForeignPublicKeyHash);

		long estimatedFee;
		try {
			estimatedFee = BTC.getInstance().estimateFee(lockTimeA * 1000L);
		} catch (BitcoinException e) {
			LOGGER.debug("Couldn't estimate Bitcoin fees?");
			return ResponseResult.BTC_NETWORK_ISSUE;
		}

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long fundsRequiredForP2shA = estimatedFee /*funding P2SH-A*/ + crossChainTradeData.expectedBitcoin - P2SH_B_OUTPUT_AMOUNT + estimatedFee /*redeeming/refunding P2SH-A*/;
		long fundsRequiredForP2shB = estimatedFee /*funding P2SH-B*/ + P2SH_B_OUTPUT_AMOUNT + estimatedFee /*redeeming/refunding P2SH-B*/;
		long totalFundsRequired = fundsRequiredForP2shA + fundsRequiredForP2shB;

		// As buildSpend also adds a fee, this is more pessimistic than required
		Transaction fundingCheckTransaction = BTC.getInstance().buildSpend(xprv58, tradeForeignAddress, totalFundsRequired);
		if (fundingCheckTransaction == null)
			return ResponseResult.INSUFFICIENT_FUNDS;

		// P2SH-A to be funded
		byte[] redeemScriptBytes = BTCP2SH.buildScript(tradeForeignPublicKeyHash, lockTimeA, crossChainTradeData.creatorBitcoinPKH, hashOfSecretA);
		String p2shAddress = BTC.getInstance().deriveP2shAddress(redeemScriptBytes);

		// Fund P2SH-A

		// Do not include fee for funding transaction as this is covered by buildSpend()
		long amountA = crossChainTradeData.expectedBitcoin - P2SH_B_OUTPUT_AMOUNT + estimatedFee /*redeeming/refunding P2SH-A*/;

		Transaction p2shFundingTransaction = BTC.getInstance().buildSpend(tradeBotData.getXprv58(), p2shAddress, amountA);
		if (p2shFundingTransaction == null) {
			LOGGER.debug("Unable to build P2SH-A funding transaction - lack of funds?");
			return ResponseResult.BTC_BALANCE_ISSUE;
		}

		try {
			BTC.getInstance().broadcastTransaction(p2shFundingTransaction);
		} catch (BitcoinException e) {
			// We couldn't fund P2SH-A at this time
			LOGGER.debug("Couldn't broadcast P2SH-A funding transaction?");
			return ResponseResult.BTC_NETWORK_ISSUE;
		}

		updateTradeBotState(repository, tradeBotData, tradeBotData.getState(),
				() -> String.format("Funding P2SH-A %s. Waiting for confirmation", p2shAddress));

		return ResponseResult.OK;
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

	@Override
	public void listen(Event event) {
		if (!(event instanceof Controller.NewBlockEvent))
			return;

		synchronized (this) {
			// Get repo for trade situations
			try (final Repository repository = RepositoryManager.getRepository()) {
				List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();

				for (TradeBotData tradeBotData : allTradeBotData) {
					repository.discardChanges();

					try {
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
					} catch (BitcoinException e) {
						LOGGER.warn(() -> String.format("Bitcoin issue processing %s: %s", tradeBotData.getAtAddress(), e.getMessage()));
					}
				}
			} catch (DataException e) {
				LOGGER.error("Couldn't run trade bot due to repository issue", e);
			}
		}
	}

	/**
	 * Trade-bot is waiting for Bob's AT to deploy.
	 * <p>
	 * If AT is deployed, then trade-bot's next step is to wait for MESSAGE from Alice.
	 */
	private void handleBobWaitingForAtConfirm(Repository repository, TradeBotData tradeBotData) throws DataException {
		if (!repository.getATRepository().exists(tradeBotData.getAtAddress())) {
			if (NTP.getTime() - tradeBotData.getTimestamp() <= MAX_AT_CONFIRMATION_PERIOD)
				return;

			// We've waited ages for AT to be confirmed into a block but something has gone awry.
			// After this long we assume transaction loss so give up with trade-bot entry too.
			tradeBotData.setState(TradeBotData.State.BOB_REFUNDED);
			tradeBotData.setTimestamp(NTP.getTime());
			// We delete trade-bot entry here instead of saving, hence not using updateTradeBotState()
			repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
			repository.saveChanges();

			LOGGER.info(() -> String.format("AT %s never confirmed. Giving up on trade", tradeBotData.getAtAddress()));
			notifyStateChange(tradeBotData);
			return;
		}

		updateTradeBotState(repository, tradeBotData, TradeBotData.State.BOB_WAITING_FOR_MESSAGE,
				() -> String.format("AT %s confirmed ready. Waiting for trade message", tradeBotData.getAtAddress()));
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
	 * @throws BitcoinException
	 */
	private void handleAliceWaitingForP2shA(Repository repository, TradeBotData tradeBotData) throws DataException, BitcoinException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		byte[] redeemScriptA = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
		String p2shAddressA = BTC.getInstance().deriveP2shAddress(redeemScriptA);

		// If AT has finished then maybe Bob cancelled his trade offer
		if (atData.getIsFinished()) {
			// No point sending MESSAGE - might as well wait for refund
			updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDING_A,
					() -> String.format("AT %s cancelled. Refunding P2SH-A %s - aborting trade", tradeBotData.getAtAddress(), p2shAddressA));
			return;
		}

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long minimumAmountA = crossChainTradeData.expectedBitcoin - P2SH_B_OUTPUT_AMOUNT;
		BTCP2SH.Status p2shStatus = BTCP2SH.determineP2shStatus(p2shAddressA, minimumAmountA);

		switch (p2shStatus) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// This shouldn't occur, but defensively check P2SH-B in case we haven't redeemed the AT
				updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_WATCH_P2SH_B,
						() -> String.format("P2SH-A %s already spent? Defensively checking P2SH-B next", p2shAddressA));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDED,
						() -> String.format("P2SH-A %s already refunded. Trade aborted", p2shAddressA));
				return;

			case FUNDED:
				// Fall-through out of switch...
				break;
		}

		// P2SH-A funding confirmed

		// Attempt to send MESSAGE to Bob's Qortal trade address
		byte[] messageData = BTCACCT.buildOfferMessage(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getHashOfSecret(), tradeBotData.getLockTimeA());
		String messageRecipient = crossChainTradeData.qortalCreatorTradeAddress;

		boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, messageData);
		if (!isMessageAlreadySent) {
			PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
			MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, messageRecipient, messageData, false, false);

			messageTransaction.computeNonce();
			messageTransaction.sign(sender);

			// reset repository state to prevent deadlock
			repository.discardChanges();
			ValidationResult result = messageTransaction.importAsUnconfirmed();

			if (result != ValidationResult.OK) {
				LOGGER.warn(() -> String.format("Unable to send MESSAGE to Bob's trade-bot %s: %s", messageRecipient, result.name()));
				return;
			}
		}

		updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_WAITING_FOR_AT_LOCK,
				() -> String.format("P2SH-A %s funding confirmed. Messaged %s. Waiting for AT %s to lock to us",
				p2shAddressA, messageRecipient, tradeBotData.getAtAddress()));
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
	 * @throws BitcoinException
	 */
	private void handleBobWaitingForMessage(Repository repository, TradeBotData tradeBotData) throws DataException, BitcoinException {
		// Fetch AT so we can determine trade start timestamp
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}

		// If AT has finished then Bob likely cancelled his trade offer
		if (atData.getIsFinished()) {
			updateTradeBotState(repository, tradeBotData, TradeBotData.State.BOB_REFUNDED,
					() -> String.format("AT %s cancelled - trading aborted", tradeBotData.getAtAddress()));
			return;
		}

		String address = tradeBotData.getTradeNativeAddress();
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null, address, null, null, null);

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
			byte[] redeemScriptA = BTCP2SH.buildScript(aliceForeignPublicKeyHash, lockTimeA, tradeBotData.getTradeForeignPublicKeyHash(), hashOfSecretA);
			String p2shAddressA = BTC.getInstance().deriveP2shAddress(redeemScriptA);

			final long minimumAmountA = tradeBotData.getBitcoinAmount() - P2SH_B_OUTPUT_AMOUNT;

			BTCP2SH.Status p2shStatus = BTCP2SH.determineP2shStatus(p2shAddressA, minimumAmountA);

			switch (p2shStatus) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
					// There might be another MESSAGE from someone else with an actually funded P2SH-A...
					continue;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					// This shouldn't occur, but defensively bump to next state
					updateTradeBotState(repository, tradeBotData, TradeBotData.State.BOB_WAITING_FOR_P2SH_B,
							() -> String.format("P2SH-A %s already spent? Defensively checking P2SH-B next", p2shAddressA));
					return;

				case REFUND_IN_PROGRESS:
				case REFUNDED:
					// This P2SH-A is burnt, but there might be another MESSAGE from someone else with an actually funded P2SH-A...
					continue;

				case FUNDED:
					// Fall-through out of switch...
					break;
			}

			// Good to go - send MESSAGE to AT

			String aliceNativeAddress = Crypto.toAddress(messageTransactionData.getCreatorPublicKey());
			int lockTimeB = BTCACCT.calcLockTimeB(messageTransactionData.getTimestamp(), lockTimeA);

			// Build outgoing message, padding each part to 32 bytes to make it easier for AT to consume
			byte[] outgoingMessageData = BTCACCT.buildTradeMessage(aliceNativeAddress, aliceForeignPublicKeyHash, hashOfSecretA, lockTimeA, lockTimeB);
			String messageRecipient = tradeBotData.getAtAddress();

			boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, outgoingMessageData);
			if (!isMessageAlreadySent) {
				PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
				MessageTransaction outgoingMessageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, messageRecipient, outgoingMessageData, false, false);

				outgoingMessageTransaction.computeNonce();
				outgoingMessageTransaction.sign(sender);

				// reset repository state to prevent deadlock
				repository.discardChanges();
				ValidationResult result = outgoingMessageTransaction.importAsUnconfirmed();

				if (result != ValidationResult.OK) {
					LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: %s", messageRecipient, result.name()));
					return;
				}
			}

			byte[] redeemScriptB = BTCP2SH.buildScript(aliceForeignPublicKeyHash, lockTimeB, tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getHashOfSecret());
			String p2shAddressB = BTC.getInstance().deriveP2shAddress(redeemScriptB);

			updateTradeBotState(repository, tradeBotData, TradeBotData.State.BOB_WAITING_FOR_P2SH_B,
					() -> String.format("Locked AT %s to %s. Waiting for P2SH-B %s", tradeBotData.getAtAddress(), aliceNativeAddress, p2shAddressB));

			return;
		}

		// Don't resave/notify if we don't need to
		if (tradeBotData.getLastTransactionSignature() != originalLastTransactionSignature)
			updateTradeBotState(repository, tradeBotData, tradeBotData.getState(), null);
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
	 * @throws BitcoinException
	 */
	private void handleAliceWaitingForAtLock(Repository repository, TradeBotData tradeBotData) throws DataException, BitcoinException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// Refund P2SH-A if AT finished (i.e. Bob cancelled trade) or we've passed lockTime-A
		if (atData.getIsFinished() || NTP.getTime() >= tradeBotData.getLockTimeA() * 1000L) {
			byte[] redeemScriptA = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
			String p2shAddressA = BTC.getInstance().deriveP2shAddress(redeemScriptA);

			long minimumAmountA = crossChainTradeData.expectedBitcoin - P2SH_B_OUTPUT_AMOUNT;
			BTCP2SH.Status p2shStatusA = BTCP2SH.determineP2shStatus(p2shAddressA, minimumAmountA);

			switch (p2shStatusA) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
					// This shouldn't occur, but defensively revert back to waiting for P2SH-A
					updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_WAITING_FOR_P2SH_A,
							() -> String.format("P2SH-A %s no longer funded? Defensively checking P2SH-A next", p2shAddressA));
					return;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					// This shouldn't occur, but defensively bump to next state
					updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_WATCH_P2SH_B,
							() -> String.format("P2SH-A %s already spent? Defensively checking P2SH-B next", p2shAddressA));
					return;

				case REFUND_IN_PROGRESS:
				case REFUNDED:
					updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDED,
							() -> String.format("P2SH-A %s already refunded. Trade aborted", p2shAddressA));
					return;

				case FUNDED:
					// Fall-through out of switch...
					break;
			}

			updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDING_A,
					() -> atData.getIsFinished()
					? String.format("AT %s cancelled. Refunding P2SH-A %s - aborting trade", tradeBotData.getAtAddress(), p2shAddressA)
					: String.format("LockTime-A reached, refunding P2SH-A %s - aborting trade", p2shAddressA));

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

			updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDING_A,
					() -> String.format("AT %s locked to %s, not us (%s). Refunding %s - aborting trade",
							tradeBotData.getAtAddress(),
							crossChainTradeData.qortalPartnerAddress,
							tradeBotData.getTradeNativeAddress(),
							p2shAddress));

			return;
		}

		// Alice needs to fund P2SH-B here

		// Find our MESSAGE to AT from previous state
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(tradeBotData.getTradeNativePublicKey(),
				crossChainTradeData.qortalCreatorTradeAddress, null, null, null);
		if (messageTransactionsData == null || messageTransactionsData.isEmpty()) {
			LOGGER.warn(() -> String.format("Unable to find our message to trade creator %s?", crossChainTradeData.qortalCreatorTradeAddress));
			return;
		}

		long recipientMessageTimestamp = messageTransactionsData.get(0).getTimestamp();
		int lockTimeA = tradeBotData.getLockTimeA();
		int lockTimeB = BTCACCT.calcLockTimeB(recipientMessageTimestamp, lockTimeA);

		// Our calculated lockTime-B should match AT's calculated lockTime-B
		if (lockTimeB != crossChainTradeData.lockTimeB) {
			LOGGER.debug(() -> String.format("Trade AT lockTime-B '%d' doesn't match our lockTime-B '%d'", crossChainTradeData.lockTimeB, lockTimeB));
			// We'll eventually refund
			return;
		}

		byte[] redeemScriptB = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddressB = BTC.getInstance().deriveP2shAddress(redeemScriptB);

		long estimatedFee = BTC.getInstance().estimateFee(lockTimeA * 1000L);

		// Have we funded P2SH-B already?
		final long minimumAmountB = P2SH_B_OUTPUT_AMOUNT + estimatedFee;

		BTCP2SH.Status p2shStatusB = BTCP2SH.determineP2shStatus(p2shAddressB, minimumAmountB);

		switch (p2shStatusB) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
			case FUNDED:
				break;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// This shouldn't occur, but defensively bump to next state
				updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_WATCH_P2SH_B,
						() -> String.format("P2SH-B %s already spent? Defensively checking P2SH-B next", p2shAddressB));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDING_A,
						() -> String.format("P2SH-B %s already refunded. Refunding P2SH-A next", p2shAddressB));
				return;
		}

		if (p2shStatusB == BTCP2SH.Status.UNFUNDED) {
			// Do not include fee for funding transaction as this is covered by buildSpend()
			long amountB = P2SH_B_OUTPUT_AMOUNT + estimatedFee /*redeeming/refunding P2SH-B*/;

			Transaction p2shFundingTransaction = BTC.getInstance().buildSpend(tradeBotData.getXprv58(), p2shAddressB, amountB);
			if (p2shFundingTransaction == null) {
				LOGGER.debug("Unable to build P2SH-B funding transaction - lack of funds?");
				return;
			}

			BTC.getInstance().broadcastTransaction(p2shFundingTransaction);
		}

		// P2SH-B funded, now we wait for Bob to redeem it
		updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_WATCH_P2SH_B,
				() -> String.format("AT %s locked to us (%s). P2SH-B %s funded. Watching P2SH-B for secret-B",
						tradeBotData.getAtAddress(), tradeBotData.getTradeNativeAddress(), p2shAddressB));
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
	 * @throws BitcoinException
	 */
	private void handleBobWaitingForP2shB(Repository repository, TradeBotData tradeBotData) throws DataException, BitcoinException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// If we've passed AT refund timestamp then AT will have finished after auto-refunding
		if (atData.getIsFinished()) {
			updateTradeBotState(repository, tradeBotData, TradeBotData.State.BOB_REFUNDED,
					() -> String.format("AT %s has auto-refunded - trade aborted", tradeBotData.getAtAddress()));

			return;
		}

		// It's possible AT hasn't processed our previous MESSAGE yet and so lockTimeB won't be set
		if (crossChainTradeData.lockTimeB == null)
			// AT yet to process MESSAGE
			return;

		byte[] redeemScriptB = BTCP2SH.buildScript(crossChainTradeData.partnerBitcoinPKH, crossChainTradeData.lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddressB = BTC.getInstance().deriveP2shAddress(redeemScriptB);

		int lockTimeA = crossChainTradeData.lockTimeA;
		long estimatedFee = BTC.getInstance().estimateFee(lockTimeA * 1000L);

		final long minimumAmountB = P2SH_B_OUTPUT_AMOUNT + estimatedFee;

		BTCP2SH.Status p2shStatusB = BTCP2SH.determineP2shStatus(p2shAddressB, minimumAmountB);

		switch (p2shStatusB) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
				// Still waiting for P2SH-B to be funded...
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// This shouldn't occur, but defensively bump to next state
				updateTradeBotState(repository, tradeBotData, TradeBotData.State.BOB_WAITING_FOR_AT_REDEEM,
						() -> String.format("P2SH-B %s already spent (exposing secret-B)? Checking AT %s for secret-A", p2shAddressB, tradeBotData.getAtAddress()));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				// AT should auto-refund - we don't need to do anything here
				return;

			case FUNDED:
				break;
		}

		// Redeem P2SH-B using secret-B
		Coin redeemAmount = Coin.valueOf(P2SH_B_OUTPUT_AMOUNT); // An actual amount to avoid dust filter, remaining used as fees. The real funds are in P2SH-A.
		ECKey redeemKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
		List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddressB);
		byte[] receivingAccountInfo = tradeBotData.getReceivingAccountInfo();

		Transaction p2shRedeemTransaction = BTCP2SH.buildRedeemTransaction(redeemAmount, redeemKey, fundingOutputs, redeemScriptB, tradeBotData.getSecret(), receivingAccountInfo);

		BTC.getInstance().broadcastTransaction(p2shRedeemTransaction);

		// P2SH-B redeemed, now we wait for Alice to use secret-A to redeem AT
		updateTradeBotState(repository, tradeBotData, TradeBotData.State.BOB_WAITING_FOR_AT_REDEEM,
				() -> String.format("P2SH-B %s redeemed (exposing secret-B). Watching AT %s for secret-A", p2shAddressB, tradeBotData.getAtAddress()));
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
	 * @throws BitcoinException
	 */
	private void handleAliceWatchingP2shB(Repository repository, TradeBotData tradeBotData) throws DataException, BitcoinException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// We check variable in AT that is set when Bob is refunded
		if (atData.getIsFinished() && crossChainTradeData.mode == BTCACCT.Mode.REFUNDED) {
			// Bob bailed out of trade so we must start refunding too
			updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDING_B,
					() -> String.format("AT %s has auto-refunded, Attempting refund also", tradeBotData.getAtAddress()));

			return;
		}

		byte[] redeemScriptB = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), crossChainTradeData.lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddressB = BTC.getInstance().deriveP2shAddress(redeemScriptB);

		int lockTimeA = crossChainTradeData.lockTimeA;
		long estimatedFee = BTC.getInstance().estimateFee(lockTimeA * 1000L);
		final long minimumAmountB = P2SH_B_OUTPUT_AMOUNT + estimatedFee;

		BTCP2SH.Status p2shStatusB = BTCP2SH.determineP2shStatus(p2shAddressB, minimumAmountB);

		switch (p2shStatusB) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
			case FUNDED:
			case REDEEM_IN_PROGRESS:
				// Still waiting for P2SH-B to be funded/redeemed...
				return;

			case REDEEMED:
				// Bob has redeemed P2SH-B, so double-check that we have redeemed AT...
				break;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				// We've refunded P2SH-B? Bump to refunding P2SH-A then
				updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDING_A,
						() -> String.format("P2SH-B %s already refunded. Refunding P2SH-A next", p2shAddressB));
				return;
		}

		List<byte[]> p2shTransactions = BTC.getInstance().getAddressTransactions(p2shAddressB);

		byte[] secretB = BTCP2SH.findP2shSecret(p2shAddressB, p2shTransactions);
		if (secretB == null)
			// Secret not revealed at this time
			return;

		// Send 'redeem' MESSAGE to AT using both secrets
		byte[] secretA = tradeBotData.getSecret();
		String qortalReceivingAddress = Base58.encode(tradeBotData.getReceivingAccountInfo()); // Actually contains whole address, not just PKH
		byte[] messageData = BTCACCT.buildRedeemMessage(secretA, secretB, qortalReceivingAddress);
		String messageRecipient = tradeBotData.getAtAddress();

		boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, messageData);
		if (!isMessageAlreadySent) {
			PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
			MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, messageRecipient, messageData, false, false);

			messageTransaction.computeNonce();
			messageTransaction.sign(sender);

			// Reset repository state to prevent deadlock
			repository.discardChanges();
			ValidationResult result = messageTransaction.importAsUnconfirmed();

			if (result != ValidationResult.OK) {
				LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: %s", messageRecipient, result.name()));
				return;
			}
		}

		updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_DONE,
				() -> String.format("P2SH-B %s redeemed, using secrets to redeem AT %s. Funds should arrive at %s",
						p2shAddressB, tradeBotData.getAtAddress(), qortalReceivingAddress));
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
	 * @throws BitcoinException
	 */
	private void handleBobWaitingForAtRedeem(Repository repository, TradeBotData tradeBotData) throws DataException, BitcoinException {
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
			// Not redeemed so must be refunded
			updateTradeBotState(repository, tradeBotData, TradeBotData.State.BOB_REFUNDED,
					() -> String.format("AT %s has auto-refunded - trade aborted", tradeBotData.getAtAddress()));

			return;
		}

		byte[] secretA = BTCACCT.findSecretA(repository, crossChainTradeData);
		if (secretA == null) {
			LOGGER.debug(() -> String.format("Unable to find secret-A from redeem message to AT %s?", tradeBotData.getAtAddress()));
			return;
		}

		// Use secret-A to redeem P2SH-A

		byte[] receivingAccountInfo = tradeBotData.getReceivingAccountInfo();
		byte[] redeemScriptA = BTCP2SH.buildScript(crossChainTradeData.partnerBitcoinPKH, crossChainTradeData.lockTimeA, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretA);
		String p2shAddressA = BTC.getInstance().deriveP2shAddress(redeemScriptA);

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long minimumAmountA = crossChainTradeData.expectedBitcoin - P2SH_B_OUTPUT_AMOUNT;
		BTCP2SH.Status p2shStatus = BTCP2SH.determineP2shStatus(p2shAddressA, minimumAmountA);

		switch (p2shStatus) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
				// P2SH-A suddenly not funded? Our best bet at this point is to hope for AT auto-refund
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// Double-check that we have redeemed P2SH-A...
				break;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				// Wait for AT to auto-refund
				return;

			case FUNDED:
				// Fall-through out of switch...
				break;
		}

		if (p2shStatus == BTCP2SH.Status.FUNDED) {
			Coin redeemAmount = Coin.valueOf(crossChainTradeData.expectedBitcoin - P2SH_B_OUTPUT_AMOUNT);
			ECKey redeemKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
			List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddressA);

			Transaction p2shRedeemTransaction = BTCP2SH.buildRedeemTransaction(redeemAmount, redeemKey, fundingOutputs, redeemScriptA, secretA, receivingAccountInfo);

			BTC.getInstance().broadcastTransaction(p2shRedeemTransaction);
		}

		String receivingAddress = BTC.getInstance().pkhToAddress(receivingAccountInfo);

		updateTradeBotState(repository, tradeBotData, TradeBotData.State.BOB_DONE,
				() -> String.format("P2SH-A %s redeemed. Funds should arrive at %s", tradeBotData.getAtAddress(), receivingAddress));
	}

	/**
	 * Trade-bot is attempting to refund P2SH-B.
	 * <p>
	 * We could potentially skip this step as P2SH-B is only funded with a token amount to cover the mining fee should Bob redeem P2SH-B.
	 * <p>
	 * Upon successful broadcast of P2SH-B refunding transaction, trade-bot's next step is to begin refunding of P2SH-A.
	 * @throws BitcoinException
	 */
	private void handleAliceRefundingP2shB(Repository repository, TradeBotData tradeBotData) throws DataException, BitcoinException {
		ATData atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
		if (atData == null) {
			LOGGER.warn(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
			return;
		}
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

		// We can't refund P2SH-B until lockTime-B has passed
		if (NTP.getTime() <= crossChainTradeData.lockTimeB * 1000L)
			return;

		// We can't refund P2SH-B until we've passed median block time
		int medianBlockTime = BTC.getInstance().getMedianBlockTime();
		if (NTP.getTime() <= medianBlockTime * 1000L)
			return;

		byte[] redeemScriptB = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), crossChainTradeData.lockTimeB, crossChainTradeData.creatorBitcoinPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddressB = BTC.getInstance().deriveP2shAddress(redeemScriptB);

		int lockTimeA = crossChainTradeData.lockTimeA;
		long estimatedFee = BTC.getInstance().estimateFee(lockTimeA * 1000L);
		final long minimumAmountB = P2SH_B_OUTPUT_AMOUNT + estimatedFee;

		BTCP2SH.Status p2shStatusB = BTCP2SH.determineP2shStatus(p2shAddressB, minimumAmountB);

		switch (p2shStatusB) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
				// Still waiting for P2SH-B to be funded...
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// We must be very close to trade timeout. Defensively try to refund P2SH-A
				updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDING_A,
						() -> String.format("P2SH-B %s already spent?. Refunding P2SH-A next", p2shAddressB));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				break;

			case FUNDED:
				break;
		}

		if (p2shStatusB == BTCP2SH.Status.FUNDED) {
			Coin refundAmount = Coin.valueOf(P2SH_B_OUTPUT_AMOUNT); // An actual amount to avoid dust filter, remaining used as fees.
			ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
			List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddressB);

			// Determine receive address for refund
			String receiveAddress = BTC.getInstance().getUnusedReceiveAddress(tradeBotData.getXprv58());
			Address receiving = Address.fromString(BTC.getInstance().getNetworkParameters(), receiveAddress);

			Transaction p2shRefundTransaction = BTCP2SH.buildRefundTransaction(refundAmount, refundKey, fundingOutputs, redeemScriptB, crossChainTradeData.lockTimeB, receiving.getHash());

			BTC.getInstance().broadcastTransaction(p2shRefundTransaction);
		}

		updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDING_A,
				() -> String.format("Refunded P2SH-B %s. Waiting for LockTime-A", p2shAddressB));
	}

	/**
	 * Trade-bot is attempting to refund P2SH-A.
	 * @throws BitcoinException
	 */
	private void handleAliceRefundingP2shA(Repository repository, TradeBotData tradeBotData) throws DataException, BitcoinException {
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
		int medianBlockTime = BTC.getInstance().getMedianBlockTime();
		if (NTP.getTime() <= medianBlockTime * 1000L)
			return;

		byte[] redeemScriptA = BTCP2SH.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorBitcoinPKH, tradeBotData.getHashOfSecret());
		String p2shAddressA = BTC.getInstance().deriveP2shAddress(redeemScriptA);

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long minimumAmountA = crossChainTradeData.expectedBitcoin - P2SH_B_OUTPUT_AMOUNT;
		BTCP2SH.Status p2shStatus = BTCP2SH.determineP2shStatus(p2shAddressA, minimumAmountA);

		switch (p2shStatus) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
				// Still waiting for P2SH-A to be funded...
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// Too late!
				updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_DONE,
						() -> String.format("P2SH-A %s already spent!", p2shAddressA));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				break;

			case FUNDED:
				// Fall-through out of switch...
				break;
		}

		if (p2shStatus == BTCP2SH.Status.FUNDED) {
			Coin refundAmount = Coin.valueOf(crossChainTradeData.expectedBitcoin - P2SH_B_OUTPUT_AMOUNT);
			ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
			List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddressA);

			// Determine receive address for refund
			String receiveAddress = BTC.getInstance().getUnusedReceiveAddress(tradeBotData.getXprv58());
			Address receiving = Address.fromString(BTC.getInstance().getNetworkParameters(), receiveAddress);

			Transaction p2shRefundTransaction = BTCP2SH.buildRefundTransaction(refundAmount, refundKey, fundingOutputs, redeemScriptA, tradeBotData.getLockTimeA(), receiving.getHash());

			BTC.getInstance().broadcastTransaction(p2shRefundTransaction);
		}

		updateTradeBotState(repository, tradeBotData, TradeBotData.State.ALICE_REFUNDED,
				() -> String.format("LockTime-A reached. Refunded P2SH-A %s. Trade aborted", p2shAddressA));
	}

	/** Updates trade-bot entry to new state, with current timestamp, logs message and notifies state-change listeners. */
	private static void updateTradeBotState(Repository repository, TradeBotData tradeBotData, TradeBotData.State newState, Supplier<String> logMessageSupplier) throws DataException {
		tradeBotData.setState(newState);
		tradeBotData.setTimestamp(NTP.getTime());
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		if (Settings.getInstance().isTradebotSystrayEnabled())
			SysTray.getInstance().showMessage("Trade-Bot", String.format("%s: %s", tradeBotData.getAtAddress(), newState.name()), MessageType.INFO);

		if (logMessageSupplier != null)
			LOGGER.info(logMessageSupplier);

		LOGGER.debug(() -> String.format("new state for trade-bot entry based on AT %s: %s", tradeBotData.getAtAddress(), newState.name()));

		notifyStateChange(tradeBotData);
	}

	private static void notifyStateChange(TradeBotData tradeBotData) {
		StateChangeEvent stateChangeEvent = new StateChangeEvent(tradeBotData);
		EventBus.INSTANCE.notify(stateChangeEvent);
	}

}
