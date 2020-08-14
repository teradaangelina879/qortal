package org.qortal.api.websocket;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.api.model.CrossChainOfferSummary;
import org.qortal.controller.Controller;
import org.qortal.crosschain.BTCACCT;
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

	private static final Map<String, BTCACCT.Mode> previousAtModes = new HashMap<>();

	// OFFERING
	private static final List<CrossChainOfferSummary> currentSummaries = new ArrayList<>();
	// REDEEMED/REFUNDED/CANCELLED
	private static final List<CrossChainOfferSummary> historicSummaries = new ArrayList<>();

	private static final Predicate<CrossChainOfferSummary> isCurrent = offerSummary
			-> offerSummary.getMode() == BTCACCT.Mode.OFFERING;

	private static final Predicate<CrossChainOfferSummary> isHistoric = offerSummary
			-> offerSummary.getMode() == BTCACCT.Mode.REDEEMED
			|| offerSummary.getMode() == BTCACCT.Mode.REFUNDED
			|| offerSummary.getMode() == BTCACCT.Mode.CANCELLED;


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
			// Find any new trade ATs since this block
			final Boolean isFinished = null;
			final Integer dataByteOffset = null;
			final Long expectedValue = null;
			final Integer minimumFinalHeight = blockData.getHeight();

			List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStates(BTCACCT.CODE_BYTES_HASH,
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
			previousAtModes.putAll(crossChainOfferSummaries.stream().collect(Collectors.toMap(CrossChainOfferSummary::getQortalAtAddress, CrossChainOfferSummary::getMode)));

			synchronized (currentSummaries) {
				// Add any OFFERING to 'current'
				currentSummaries.addAll(crossChainOfferSummaries.stream().filter(isCurrent).collect(Collectors.toList()));
			}

			final long tooOldTimestamp = NTP.getTime() - 24 * 60 * 60 * 1000L;
			synchronized (historicSummaries) {
				// Add any REDEEMED/REFUNDED/CANCELLED
				historicSummaries.addAll(crossChainOfferSummaries.stream().filter(isHistoric).collect(Collectors.toList()));

				// But also remove any that are over 24 hours old
				historicSummaries.removeIf(offerSummary -> offerSummary.getTimestamp() < tooOldTimestamp);
			}
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

		List<CrossChainOfferSummary> crossChainOfferSummaries;

		synchronized (currentSummaries) {
			crossChainOfferSummaries = new ArrayList<>(currentSummaries);
		}

		if (includeHistoric)
			synchronized (historicSummaries) {
				crossChainOfferSummaries.addAll(historicSummaries);
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
		Integer dataByteOffset = BTCACCT.MODE_BYTE_OFFSET;
		Long expectedValue = (long) BTCACCT.Mode.OFFERING.value;
		Integer minimumFinalHeight = null;

		List<ATStateData> initialAtStates = repository.getATRepository().getMatchingFinalATStates(BTCACCT.CODE_BYTES_HASH,
				isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
				null, null, null);

		if (initialAtStates == null)
			throw new DataException("Couldn't fetch current trades from repository");

		// Save initial AT modes
		previousAtModes.putAll(initialAtStates.stream().collect(Collectors.toMap(ATStateData::getATAddress, atState -> BTCACCT.Mode.OFFERING)));

		// Convert to offer summaries
		currentSummaries.addAll(produceSummaries(repository, initialAtStates, null));
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

		List<ATStateData> historicAtStates = repository.getATRepository().getMatchingFinalATStates(BTCACCT.CODE_BYTES_HASH,
				isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
				null, null, null);

		if (historicAtStates == null)
			throw new DataException("Couldn't fetch historic trades from repository");

		for (ATStateData historicAtState : historicAtStates) {
			CrossChainOfferSummary historicOfferSummary = produceSummary(repository, historicAtState, null);

			switch (historicOfferSummary.getMode()) {
				case REDEEMED:
				case REFUNDED:
				case CANCELLED:
					break;

				default:
					continue;
			}

			// Add summary to initial burst
			historicSummaries.add(historicOfferSummary);

			// Save initial AT mode
			previousAtModes.put(historicAtState.getATAddress(), historicOfferSummary.getMode());
		}
	}

	private static CrossChainOfferSummary produceSummary(Repository repository, ATStateData atState, Long timestamp) throws DataException {
		CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atState);

		long atStateTimestamp;

		if (crossChainTradeData.mode == BTCACCT.Mode.OFFERING)
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
