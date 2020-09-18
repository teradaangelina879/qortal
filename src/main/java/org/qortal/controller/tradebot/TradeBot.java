package org.qortal.controller.tradebot;

import java.awt.TrayIcon.MessageType;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Supplier;
import org.bitcoinj.core.ECKey;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.controller.Controller;
import org.qortal.controller.tradebot.AcctTradeBot.ResponseResult;
import org.qortal.crosschain.ACCT;
import org.qortal.crosschain.BitcoinACCTv1;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.LitecoinACCTv1;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;
import org.qortal.gui.SysTray;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;

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
		// acctTradeBotSuppliers.put(LitecoinACCTv1.class, LitecoinACCTv1TradeBot::getInstance);
	}

	private static TradeBot instance;

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

		if (canDelete)
			repository.getCrossChainRepository().delete(tradePrivateKey);

		return canDelete;
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof Controller.NewBlockEvent))
			return;

		synchronized (this) {
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
					LOGGER.warn(() -> String.format("Bitcoin issue processing trade-bot entry for AT %s: %s", tradeBotData.getAtAddress(), e.getMessage()));
				}
		}
	}

	/*package*/ static byte[] generateTradePrivateKey() {
		// The private key is used for both Curve25519 and secp256k1 so needs to be valid for both.
		// Curve25519 accepts any seed, so generate a valid secp256k1 key and use that.
		return new ECKey().getPrivKeyBytes();
	}

	/*package*/ static byte[] deriveTradeNativePublicKey(byte[] privateKey) {
		return PrivateKeyAccount.toPublicKey(privateKey);
	}

	/*package*/ static byte[] deriveTradeForeignPublicKey(byte[] privateKey) {
		return ECKey.fromPrivate(privateKey).getPubKey();
	}

	/*package*/ static byte[] generateSecret() {
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		return secret;
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

}
