package org.qortal.controller.tradebot;

import java.awt.TrayIcon.MessageType;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.ECKey;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.controller.Controller;
import org.qortal.controller.Synchronizer;
import org.qortal.controller.tradebot.AcctTradeBot.ResponseResult;
import org.qortal.crosschain.*;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.network.OnlineTradeData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;
import org.qortal.gui.SysTray;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.GetOnlineTradesMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.OnlineTradesMessage;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.hsqldb.HSQLDBImportExport;
import org.qortal.settings.Settings;
import org.qortal.utils.ByteArray;
import org.qortal.utils.NTP;

import com.google.common.primitives.Longs;

/**
 * Performing cross-chain trading steps on behalf of user.
 * <p>
 * We deal with three different independent state-spaces here:
 * <ul>
 * 	<li>Qortal blockchain</li>
 * 	<li>Foreign blockchain</li>
 * 	<li>Trade-bot entries</li>
 * </ul>
 */
public class TradeBot implements Listener {

	private static final Logger LOGGER = LogManager.getLogger(TradeBot.class);
	private static final Random RANDOM = new SecureRandom();

	private static final long ONLINE_LIFETIME = 30 * 60 * 1000L; // 30 minutes in ms

	private static final long ONLINE_BROADCAST_INTERVAL = 5 * 60 * 1000L; // 5 minutes in ms

	public interface StateNameAndValueSupplier {
		public String getState();
		public int getStateValue();
	}

	public static class StateChangeEvent implements Event {
		private final TradeBotData tradeBotData;

		public StateChangeEvent(TradeBotData tradeBotData) {
			this.tradeBotData = tradeBotData;
		}

		public TradeBotData getTradeBotData() {
			return this.tradeBotData;
		}
	}

	private static final Map<Class<? extends ACCT>, Supplier<AcctTradeBot>> acctTradeBotSuppliers = new HashMap<>();
	static {
		acctTradeBotSuppliers.put(BitcoinACCTv1.class, BitcoinACCTv1TradeBot::getInstance);
		acctTradeBotSuppliers.put(LitecoinACCTv1.class, LitecoinACCTv1TradeBot::getInstance);
		acctTradeBotSuppliers.put(LitecoinACCTv2.class, LitecoinACCTv2TradeBot::getInstance);
		acctTradeBotSuppliers.put(LitecoinACCTv3.class, LitecoinACCTv3TradeBot::getInstance);
		acctTradeBotSuppliers.put(DogecoinACCTv1.class, DogecoinACCTv1TradeBot::getInstance);
		acctTradeBotSuppliers.put(DogecoinACCTv2.class, DogecoinACCTv2TradeBot::getInstance);
		acctTradeBotSuppliers.put(DogecoinACCTv3.class, DogecoinACCTv3TradeBot::getInstance);
	}

	private static TradeBot instance;

	private final Map<ByteArray, Long> ourTimestampsByPubkey = Collections.synchronizedMap(new HashMap<>());
	private final List<OnlineTradeData> pendingOnlineSignatures = Collections.synchronizedList(new ArrayList<>());

	private final Map<ByteArray, OnlineTradeData> allOnlineByPubkey = Collections.synchronizedMap(new HashMap<>());
	private Map<ByteArray, OnlineTradeData> safeAllOnlineByPubkey = Collections.emptyMap();
	private long nextBroadcastTimestamp = 0L;

	private TradeBot() {
		EventBus.INSTANCE.addListener(event -> TradeBot.getInstance().listen(event));
	}

	public static synchronized TradeBot getInstance() {
		if (instance == null)
			instance = new TradeBot();

		return instance;
	}

	public ACCT getAcctUsingAtData(ATData atData) {
		byte[] codeHash = atData.getCodeHash();
		if (codeHash == null)
			return null;

		return SupportedBlockchain.getAcctByCodeHash(codeHash);
	}

	public CrossChainTradeData populateTradeData(Repository repository, ATData atData) throws DataException {
		ACCT acct = this.getAcctUsingAtData(atData);
		if (acct == null)
			return null;

		return acct.populateTradeData(repository, atData);
	}

	/**
	 * Creates a new trade-bot entry from the "Bob" viewpoint,
	 * i.e. OFFERing QORT in exchange for foreign blockchain currency.
	 * <p>
	 * Generates:
	 * <ul>
	 * 	<li>new 'trade' private key</li>
	 * 	<li>secret(s)</li>
	 * </ul>
	 * Derives:
	 * <ul>
	 * 	<li>'native' (as in Qortal) public key, public key hash, address (starting with Q)</li>
	 * 	<li>'foreign' public key, public key hash</li>
	 *	<li>hash(es) of secret(s)</li>
	 * </ul>
	 * A Qortal AT is then constructed including the following as constants in the 'data segment':
	 * <ul>
	 * 	<li>'native' (Qortal) 'trade' address - used to MESSAGE AT</li>
	 * 	<li>'foreign' public key hash - used by Alice's to allow redeem of currency on foreign blockchain</li>
	 * 	<li>hash(es) of secret(s) - used by AT (optional) and foreign blockchain as needed</li>
	 * 	<li>QORT amount on offer by Bob</li>
	 * 	<li>foreign currency amount expected in return by Bob (from Alice)</li>
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
	public byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) throws DataException {
		// Fetch latest ACCT version for requested foreign blockchain
		ACCT acct = tradeBotCreateRequest.foreignBlockchain.getLatestAcct();

		AcctTradeBot acctTradeBot = findTradeBotForAcct(acct);
		if (acctTradeBot == null)
			return null;

		return acctTradeBot.createTrade(repository, tradeBotCreateRequest);
	}

	/**
	 * Creates a trade-bot entry from the 'Alice' viewpoint,
	 * i.e. matching foreign blockchain currency to an existing QORT offer.
	 * <p>
	 * Requires a chosen trade offer from Bob, passed by <tt>crossChainTradeData</tt>
	 * and access to a foreign blockchain wallet via <tt>foreignKey</tt>.
	 * <p>
	 * @param repository
	 * @param crossChainTradeData chosen trade OFFER that Alice wants to match
	 * @param foreignKey foreign blockchain wallet key
	 * @throws DataException
	 */
	public ResponseResult startResponse(Repository repository, ATData atData, ACCT acct,
			CrossChainTradeData crossChainTradeData, String foreignKey, String receivingAddress) throws DataException {
		AcctTradeBot acctTradeBot = findTradeBotForAcct(acct);
		if (acctTradeBot == null) {
			LOGGER.debug(() -> String.format("Couldn't find ACCT trade-bot for AT %s", atData.getATAddress()));
			return ResponseResult.NETWORK_ISSUE;
		}

		// Check Alice doesn't already have an existing, on-going trade-bot entry for this AT.
		if (repository.getCrossChainRepository().existsTradeWithAtExcludingStates(atData.getATAddress(), acctTradeBot.getEndStates()))
			return ResponseResult.TRADE_ALREADY_EXISTS;

		return acctTradeBot.startResponse(repository, atData, acct, crossChainTradeData, foreignKey, receivingAddress);
	}

	public boolean deleteEntry(Repository repository, byte[] tradePrivateKey) throws DataException {
		TradeBotData tradeBotData = repository.getCrossChainRepository().getTradeBotData(tradePrivateKey);
		if (tradeBotData == null)
			// Can't delete what we don't have!
			return false;

		boolean canDelete = false;

		ACCT acct = SupportedBlockchain.getAcctByName(tradeBotData.getAcctName());
		if (acct == null)
			// We can't/no longer support this ACCT
			canDelete = true;
		else {
			AcctTradeBot acctTradeBot = findTradeBotForAcct(acct);
			canDelete = acctTradeBot == null || acctTradeBot.canDelete(repository, tradeBotData);
		}

		if (canDelete) {
			repository.getCrossChainRepository().delete(tradePrivateKey);
			repository.saveChanges();
		}

		return canDelete;
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof Synchronizer.NewChainTipEvent))
			return;

		synchronized (this) {
			expireOldOnlineSignatures();

			List<TradeBotData> allTradeBotData;

			try (final Repository repository = RepositoryManager.getRepository()) {
				allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			} catch (DataException e) {
				LOGGER.error("Couldn't run trade bot due to repository issue", e);
				return;
			}

			for (TradeBotData tradeBotData : allTradeBotData)
				try (final Repository repository = RepositoryManager.getRepository()) {
					// Find ACCT-specific trade-bot for this entry
					ACCT acct = SupportedBlockchain.getAcctByName(tradeBotData.getAcctName());
					if (acct == null) {
						LOGGER.debug(() -> String.format("Couldn't find ACCT matching name %s", tradeBotData.getAcctName()));
						continue;
					}

					AcctTradeBot acctTradeBot = findTradeBotForAcct(acct);
					if (acctTradeBot == null) {
						LOGGER.debug(() -> String.format("Couldn't find ACCT trade-bot matching name %s", tradeBotData.getAcctName()));
						continue;
					}

					acctTradeBot.progress(repository, tradeBotData);
				} catch (DataException e) {
					LOGGER.error("Couldn't run trade bot due to repository issue", e);
				} catch (ForeignBlockchainException e) {
					LOGGER.warn(() -> String.format("Foreign blockchain issue processing trade-bot entry for AT %s: %s", tradeBotData.getAtAddress(), e.getMessage()));
				}

			broadcastOnlineSignatures();
		}
	}

	public static byte[] generateTradePrivateKey() {
		// The private key is used for both Curve25519 and secp256k1 so needs to be valid for both.
		// Curve25519 accepts any seed, so generate a valid secp256k1 key and use that.
		return new ECKey().getPrivKeyBytes();
	}

	public static byte[] deriveTradeNativePublicKey(byte[] privateKey) {
		return PrivateKeyAccount.toPublicKey(privateKey);
	}

	public static byte[] deriveTradeForeignPublicKey(byte[] privateKey) {
		return ECKey.fromPrivate(privateKey).getPubKey();
	}

	/*package*/ static byte[] generateSecret() {
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		return secret;
	}

	/*package*/ static void backupTradeBotData(Repository repository, List<TradeBotData> additional) {
		// Attempt to backup the trade bot data. This an optional step and doesn't impact trading, so don't throw an exception on failure
		try {
			LOGGER.info("About to backup trade bot data...");
			HSQLDBImportExport.backupTradeBotStates(repository, additional);
		} catch (DataException e) {
			LOGGER.info(String.format("Repository issue when exporting trade bot data: %s", e.getMessage()));
		}
	}

	/** Updates trade-bot entry to new state, with current timestamp, logs message and notifies state-change listeners. */
	/*package*/ static void updateTradeBotState(Repository repository, TradeBotData tradeBotData,
			String newState, int newStateValue, Supplier<String> logMessageSupplier) throws DataException {
		tradeBotData.setState(newState);
		tradeBotData.setStateValue(newStateValue);
		tradeBotData.setTimestamp(NTP.getTime());
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		if (Settings.getInstance().isTradebotSystrayEnabled())
			SysTray.getInstance().showMessage("Trade-Bot", String.format("%s: %s", tradeBotData.getAtAddress(), newState), MessageType.INFO);

		if (logMessageSupplier != null)
			LOGGER.info(logMessageSupplier);

		LOGGER.debug(() -> String.format("new state for trade-bot entry based on AT %s: %s", tradeBotData.getAtAddress(), newState));

		notifyStateChange(tradeBotData);
	}

	/** Updates trade-bot entry to new state, with current timestamp, logs message and notifies state-change listeners. */
	/*package*/ static void updateTradeBotState(Repository repository, TradeBotData tradeBotData, StateNameAndValueSupplier newStateSupplier, Supplier<String> logMessageSupplier) throws DataException {
		updateTradeBotState(repository, tradeBotData, newStateSupplier.getState(), newStateSupplier.getStateValue(), logMessageSupplier);
	}

	/** Updates trade-bot entry to new state, with current timestamp, logs message and notifies state-change listeners. */
	/*package*/ static void updateTradeBotState(Repository repository, TradeBotData tradeBotData, Supplier<String> logMessageSupplier) throws DataException {
		updateTradeBotState(repository, tradeBotData, tradeBotData.getState(), tradeBotData.getStateValue(), logMessageSupplier);
	}

	/*package*/ static void notifyStateChange(TradeBotData tradeBotData) {
		StateChangeEvent stateChangeEvent = new StateChangeEvent(tradeBotData);
		EventBus.INSTANCE.notify(stateChangeEvent);
	}

	/*package*/ static AcctTradeBot findTradeBotForAcct(ACCT acct) {
		Supplier<AcctTradeBot> acctTradeBotSupplier = acctTradeBotSuppliers.get(acct.getClass());
		if (acctTradeBotSupplier == null)
			return null;

		return acctTradeBotSupplier.get();
	}

	// PRESENCE-related

	private void expireOldOnlineSignatures() {
		long now = NTP.getTime();

		synchronized (this.pendingOnlineSignatures) {
			this.pendingOnlineSignatures.removeIf(onlineTradeData -> onlineTradeData.getTimestamp() <= now);
		}
	}

	/*package*/ void updatePresence(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException {
		String atAddress = tradeBotData.getAtAddress();

		PrivateKeyAccount tradeNativeAccount = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
		String signerAddress = tradeNativeAccount.getAddress();

		/*
		 * We only broadcast trade entry online signatures for BOB when OFFERING
		 * so that buyers don't click on offline / expired entries that would waste their time.
		 */
		if (tradeData.mode != AcctMode.OFFERING || !signerAddress.equals(tradeData.qortalCreatorTradeAddress))
			return;

		long now = NTP.getTime();

		// Timestamps are considered good for full lifetime...
		long expiry = (now + ONLINE_LIFETIME) % ONLINE_LIFETIME;
		// ... but refresh if older than half-lifetime
		long threshold = (now + ONLINE_LIFETIME / 2) % (ONLINE_LIFETIME / 2);

		ByteArray pubkeyByteArray = ByteArray.of(tradeNativeAccount.getPublicKey());
		// If map's timestamp is missing, or too old, use the new timestamp - otherwise use existing timestamp.
		long timestamp = ourTimestampsByPubkey.compute(pubkeyByteArray, (k, v) -> (v == null || v <= threshold) ? expiry : v);

		// If timestamp hasn't been updated then nothing to do
		if (timestamp != expiry)
			return;

		// Create signature
		byte[] signature = tradeNativeAccount.sign(Longs.toByteArray(timestamp));

		// Add new online info to queue to be broadcast around network
		OnlineTradeData onlineTradeData = new OnlineTradeData(timestamp, tradeNativeAccount.getPublicKey(), signature, atAddress);
		this.pendingOnlineSignatures.add(onlineTradeData);

		this.allOnlineByPubkey.put(pubkeyByteArray, onlineTradeData);
		rebuildSafeAllOnline();
	}

	private void rebuildSafeAllOnline() {
		synchronized (this.allOnlineByPubkey) {
			// Collect into a *new* unmodifiable map.
			this.safeAllOnlineByPubkey = Map.copyOf(this.allOnlineByPubkey);
		}
	}

	private void broadcastOnlineSignatures() {
		// If we have new online signatures that are pending broadcast, send those as a priority
		if (!this.pendingOnlineSignatures.isEmpty()) {
			// Create a copy for Network to safely use in another thread
			List<OnlineTradeData> safeOnlineSignatures;
			synchronized (this.pendingOnlineSignatures) {
				safeOnlineSignatures = List.copyOf(this.pendingOnlineSignatures);
				this.pendingOnlineSignatures.clear();
			}

			OnlineTradesMessage onlineTradesMessage = new OnlineTradesMessage(safeOnlineSignatures);
			Network.getInstance().broadcast(peer -> onlineTradesMessage);

			return;
		}

		// As we have no new online signatures, check whether it's time to do a general broadcast
		Long now = NTP.getTime();
		if (now == null || now < nextBroadcastTimestamp)
			return;

		nextBroadcastTimestamp = now + ONLINE_BROADCAST_INTERVAL;

		List<OnlineTradeData> safeOnlineSignatures = List.copyOf(this.safeAllOnlineByPubkey.values());

		GetOnlineTradesMessage getOnlineTradesMessage = new GetOnlineTradesMessage(safeOnlineSignatures);
		Network.getInstance().broadcast(peer -> getOnlineTradesMessage);
	}

	// Network message processing

	public void onGetOnlineTradesMessage(Peer peer, Message message) {
		GetOnlineTradesMessage getOnlineTradesMessage = (GetOnlineTradesMessage) message;

		List<OnlineTradeData> peersOnlineTrades = getOnlineTradesMessage.getOnlineTrades();

		Map<ByteArray, OnlineTradeData> entriesUnknownToPeer = new HashMap<>(this.safeAllOnlineByPubkey);
		for (OnlineTradeData peersOnlineTrade : peersOnlineTrades) {
			ByteArray pubkeyByteArray = ByteArray.of(peersOnlineTrade.getPublicKey());

			OnlineTradeData ourEntry = entriesUnknownToPeer.get(pubkeyByteArray);

			if (ourEntry != null && ourEntry.getTimestamp() == peersOnlineTrade.getTimestamp())
				entriesUnknownToPeer.remove(pubkeyByteArray);
		}

		// Send complement to peer
		List<OnlineTradeData> safeOnlineSignatures = List.copyOf(entriesUnknownToPeer.values());
		Message responseMessage = new OnlineTradesMessage(safeOnlineSignatures);
		if (!peer.sendMessage(responseMessage)) {
			peer.disconnect("failed to send online trades response");
			return;
		}
	}

	public void onOnlineTradesMessage(Peer peer, Message message) {
		OnlineTradesMessage onlineTradesMessage = (OnlineTradesMessage) message;

		List<OnlineTradeData> peersOnlineTrades = onlineTradesMessage.getOnlineTrades();

		long now = NTP.getTime();
		// Timestamps after this are too far into the future
		long futureThreshold = (now % ONLINE_LIFETIME) + ONLINE_LIFETIME + ONLINE_LIFETIME / 2;
		// Timestamps before this are too far into the past
		long pastThreshold = now;

		Map<ByteArray, Supplier<ACCT>> acctSuppliersByCodeHash = SupportedBlockchain.getAcctMap();

		int newCount = 0;

		try (final Repository repository = RepositoryManager.getRepository()) {
			for (OnlineTradeData peersOnlineTrade : peersOnlineTrades) {
				long timestamp = peersOnlineTrade.getTimestamp();

				if (timestamp < pastThreshold || timestamp > futureThreshold)
					continue;

				ByteArray pubkeyByteArray = ByteArray.of(peersOnlineTrade.getPublicKey());

				// Ignore if we've previously verified this timestamp+publickey combo
				OnlineTradeData existingTradeData = this.safeAllOnlineByPubkey.get(pubkeyByteArray);
				if (existingTradeData != null && existingTradeData.getTimestamp() == timestamp)
					continue;

				// Check timestamp signature
				byte[] timestampSignature = peersOnlineTrade.getSignature();
				byte[] timestampBytes = Longs.toByteArray(timestamp);
				byte[] publicKey = peersOnlineTrade.getPublicKey();
				if (!Crypto.verify(publicKey, timestampSignature, timestampBytes))
					continue;

				ATData atData = repository.getATRepository().fromATAddress(peersOnlineTrade.getAtAddress());
				if (atData == null || atData.getIsFrozen() || atData.getIsFinished())
					continue;

				ByteArray atCodeHash = new ByteArray(atData.getCodeHash());
				Supplier<ACCT> acctSupplier = acctSuppliersByCodeHash.get(atCodeHash);
				if (acctSupplier == null)
					continue;

				CrossChainTradeData tradeData = acctSupplier.get().populateTradeData(repository, atData);
				if (tradeData == null)
					continue;

				// Convert signer's public key to address form
				String signerAddress = Crypto.toAddress(publicKey);

				// Signer's public key (in address form) must match Bob's / Alice's trade public key (in address form)
				if (!signerAddress.equals(tradeData.qortalCreatorTradeAddress) && !signerAddress.equals(tradeData.qortalPartnerAddress))
					continue;

				// This is new to us
				this.allOnlineByPubkey.put(pubkeyByteArray, peersOnlineTrade);
				++newCount;
			}
		} catch (DataException e) {
			LOGGER.error("Couldn't process ONLINE_TRADES message due to repository issue", e);
		}

		if (newCount > 0) {
			LOGGER.debug("New online trade signatures: {}", newCount);
			rebuildSafeAllOnline();
		}
	}
}
