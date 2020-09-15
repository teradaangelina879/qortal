package org.qortal.api.websocket;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.api.model.CrossChainOfferSummary;
import org.qortal.controller.Controller;
import org.qortal.crosschain.BitcoinACCTv1;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.NTP;

@WebSocket
@SuppressWarnings("serial")
public class TradeOffersWebSocket extends ApiWebSocket implements Listener {

	private static final Logger LOGGER = LogManager.getLogger(TradeOffersWebSocket.class);

	private static final Map<String, BitcoinACCTv1.Mode> previousAtModes = new HashMap<>();

	// OFFERING
	private static final Map<String, CrossChainOfferSummary> currentSummaries = new HashMap<>();
	// REDEEMED/REFUNDED/CANCELLED
	private static final Map<String, CrossChainOfferSummary> historicSummaries = new HashMap<>();

	private static final Predicate<CrossChainOfferSummary> isHistoric = offerSummary
			-> offerSummary.getMode() == BitcoinACCTv1.Mode.REDEEMED
			|| offerSummary.getMode() == BitcoinACCTv1.Mode.REFUNDED
			|| offerSummary.getMode() == BitcoinACCTv1.Mode.CANCELLED;


	@Override
	public void configure(WebSocketServletFactory factory) {
		factory.register(TradeOffersWebSocket.class);

		try (final Repository repository = RepositoryManager.getRepository()) {
			populateCurrentSummaries(repository);

			populateHistoricSummaries(repository);
		} catch (DataException e) {
			// How to fail properly?
			return;
		}

		EventBus.INSTANCE.addListener(this::listen);
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof Controller.NewBlockEvent))
			return;

		BlockData blockData = ((Controller.NewBlockEvent) event).getBlockData();

		// Process any new info
		List<CrossChainOfferSummary> crossChainOfferSummaries;

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Find any new/changed trade ATs since this block
			final Boolean isFinished = null;
			final Integer dataByteOffset = null;
			final Long expectedValue = null;
			final Integer minimumFinalHeight = blockData.getHeight();

			List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStates(BitcoinACCTv1.CODE_BYTES_HASH,
					isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
					null, null, null);

			if (atStates == null)
				return;

			crossChainOfferSummaries = produceSummaries(repository, atStates, blockData.getTimestamp());
		} catch (DataException e) {
			// No output this time
			return;
		}

		synchronized (previousAtModes) {
			// Remove any entries unchanged from last time
			crossChainOfferSummaries.removeIf(offerSummary -> previousAtModes.get(offerSummary.getQortalAtAddress()) == offerSummary.getMode());

			// Don't send anything if no results
			if (crossChainOfferSummaries.isEmpty())
				return;

			// Update
			for (CrossChainOfferSummary offerSummary : crossChainOfferSummaries) {
				previousAtModes.put(offerSummary.qortalAtAddress, offerSummary.getMode());
				LOGGER.trace(() -> String.format("Block height: %d, AT: %s, mode: %s", blockData.getHeight(), offerSummary.qortalAtAddress, offerSummary.getMode().name()));

				switch (offerSummary.getMode()) {
					case OFFERING:
						currentSummaries.put(offerSummary.qortalAtAddress, offerSummary);
						historicSummaries.remove(offerSummary.qortalAtAddress);
						break;

					case REDEEMED:
					case REFUNDED:
					case CANCELLED:
						currentSummaries.remove(offerSummary.qortalAtAddress);
						historicSummaries.put(offerSummary.qortalAtAddress, offerSummary);
						break;

					case TRADING:
						currentSummaries.remove(offerSummary.qortalAtAddress);
						historicSummaries.remove(offerSummary.qortalAtAddress);
						break;
				}
			}

			// Remove any historic offers that are over 24 hours old
			final long tooOldTimestamp = NTP.getTime() - 24 * 60 * 60 * 1000L;
			historicSummaries.values().removeIf(historicSummary -> historicSummary.getTimestamp() < tooOldTimestamp);
		}

		// Notify sessions
		for (Session session : getSessions())
			sendOfferSummaries(session, crossChainOfferSummaries);
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
		final boolean includeHistoric = queryParams.get("includeHistoric") != null;

		List<CrossChainOfferSummary> crossChainOfferSummaries = new ArrayList<>();

		synchronized (previousAtModes) {
			crossChainOfferSummaries.addAll(currentSummaries.values());

			if (includeHistoric)
				crossChainOfferSummaries.addAll(historicSummaries.values());
		}

		if (!sendOfferSummaries(session, crossChainOfferSummaries)) {
			session.close(4002, "websocket issue");
			return;
		}

		super.onWebSocketConnect(session);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		super.onWebSocketClose(session, statusCode, reason);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* ignored */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		/* ignored */
	}

	private boolean sendOfferSummaries(Session session, List<CrossChainOfferSummary> crossChainOfferSummaries) {
		try {
			StringWriter stringWriter = new StringWriter();
			marshall(stringWriter, crossChainOfferSummaries);

			String output = stringWriter.toString();
			session.getRemote().sendStringByFuture(output);
		} catch (IOException e) {
			// No output this time?
			return false;
		}

		return true;
	}

	private static void populateCurrentSummaries(Repository repository) throws DataException {
		// We want ALL OFFERING trades
		Boolean isFinished = Boolean.FALSE;
		Integer dataByteOffset = BitcoinACCTv1.MODE_BYTE_OFFSET;
		Long expectedValue = (long) BitcoinACCTv1.Mode.OFFERING.value;
		Integer minimumFinalHeight = null;

		List<ATStateData> initialAtStates = repository.getATRepository().getMatchingFinalATStates(BitcoinACCTv1.CODE_BYTES_HASH,
				isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
				null, null, null);

		if (initialAtStates == null)
			throw new DataException("Couldn't fetch current trades from repository");

		// Save initial AT modes
		previousAtModes.putAll(initialAtStates.stream().collect(Collectors.toMap(ATStateData::getATAddress, atState -> BitcoinACCTv1.Mode.OFFERING)));

		// Convert to offer summaries
		currentSummaries.putAll(produceSummaries(repository, initialAtStates, null).stream().collect(Collectors.toMap(CrossChainOfferSummary::getQortalAtAddress, offerSummary -> offerSummary)));
	}

	private static void populateHistoricSummaries(Repository repository) throws DataException {
		// We want REDEEMED/REFUNDED/CANCELLED trades over the last 24 hours
		long timestamp = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
		int minimumFinalHeight = repository.getBlockRepository().getHeightFromTimestamp(timestamp);

		if (minimumFinalHeight == 0)
			throw new DataException("Couldn't fetch block timestamp from repository");

		Boolean isFinished = Boolean.TRUE;
		Integer dataByteOffset = null;
		Long expectedValue = null;
		++minimumFinalHeight; // because height is just *before* timestamp

		List<ATStateData> historicAtStates = repository.getATRepository().getMatchingFinalATStates(BitcoinACCTv1.CODE_BYTES_HASH,
				isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
				null, null, null);

		if (historicAtStates == null)
			throw new DataException("Couldn't fetch historic trades from repository");

		for (ATStateData historicAtState : historicAtStates) {
			CrossChainOfferSummary historicOfferSummary = produceSummary(repository, historicAtState, null);

			if (!isHistoric.test(historicOfferSummary))
				continue;

			// Add summary to initial burst
			historicSummaries.put(historicOfferSummary.getQortalAtAddress(), historicOfferSummary);

			// Save initial AT mode
			previousAtModes.put(historicOfferSummary.getQortalAtAddress(), historicOfferSummary.getMode());
		}
	}

	private static CrossChainOfferSummary produceSummary(Repository repository, ATStateData atState, Long timestamp) throws DataException {
		CrossChainTradeData crossChainTradeData = BitcoinACCTv1.populateTradeData(repository, atState);

		long atStateTimestamp;

		if (crossChainTradeData.mode == BitcoinACCTv1.Mode.OFFERING)
			// We want when trade was created, not when it was last updated
			atStateTimestamp = atState.getCreation();
		else
			atStateTimestamp = timestamp != null ? timestamp : repository.getBlockRepository().getTimestampFromHeight(atState.getHeight());

		return new CrossChainOfferSummary(crossChainTradeData, atStateTimestamp);
	}

	private static List<CrossChainOfferSummary> produceSummaries(Repository repository, List<ATStateData> atStates, Long timestamp) throws DataException {
		List<CrossChainOfferSummary> offerSummaries = new ArrayList<>();

		for (ATStateData atState : atStates)
			offerSummaries.add(produceSummary(repository, atState, timestamp));

		return offerSummaries;
	}

}
