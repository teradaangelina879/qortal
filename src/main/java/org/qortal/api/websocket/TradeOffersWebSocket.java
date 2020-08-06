package org.qortal.api.websocket;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.api.model.BlockInfo;
import org.qortal.api.model.CrossChainOfferSummary;
import org.qortal.controller.BlockNotifier;
import org.qortal.crosschain.BTCACCT;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.NTP;

@WebSocket
@SuppressWarnings("serial")
public class TradeOffersWebSocket extends ApiWebSocket {

	@Override
	public void configure(WebSocketServletFactory factory) {
		factory.register(TradeOffersWebSocket.class);
	}

	@OnWebSocketConnect
	public void onWebSocketConnect(Session session) {
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();

		final boolean includeHistoric = queryParams.get("includeHistoric") != null;
		final Map<String, BTCACCT.Mode> previousAtModes = new HashMap<>();
		List<CrossChainOfferSummary> crossChainOfferSummaries;

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<ATStateData> initialAtStates;

			// We want ALL OFFERING trades
			Boolean isFinished = Boolean.FALSE;
			Integer dataByteOffset = BTCACCT.MODE_BYTE_OFFSET;
			Long expectedValue = (long) BTCACCT.Mode.OFFERING.value;
			Integer minimumFinalHeight = null;

			initialAtStates = repository.getATRepository().getMatchingFinalATStates(BTCACCT.CODE_BYTES_HASH,
					isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
					null, null, null);

			if (initialAtStates == null) {
				session.close(4001, "repository issue fetching OFFERING trades");
				return;
			}

			// Save initial AT modes
			previousAtModes.putAll(initialAtStates.stream().collect(Collectors.toMap(ATStateData::getATAddress, atState -> BTCACCT.Mode.OFFERING)));

			// Convert to offer summaries
			crossChainOfferSummaries = produceSummaries(repository, initialAtStates, null);

			if (includeHistoric) {
				// We also want REDEEMED/REFUNDED/CANCELLED trades over the last 24 hours
				long timestamp = NTP.getTime() - 24 * 60 * 60 * 1000L;
				minimumFinalHeight = repository.getBlockRepository().getHeightFromTimestamp(timestamp);

				if (minimumFinalHeight != 0) {
					isFinished = Boolean.TRUE;
					dataByteOffset = null;
					expectedValue = null;
					++minimumFinalHeight; // because height is just *before* timestamp

					List<ATStateData> historicAtStates = repository.getATRepository().getMatchingFinalATStates(BTCACCT.CODE_BYTES_HASH,
							isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
							null, null, null);

					if (historicAtStates == null) {
						session.close(4002, "repository issue fetching historic trades");
						return;
					}

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
						crossChainOfferSummaries.add(historicOfferSummary);

						// Save initial AT mode
						previousAtModes.put(historicAtState.getATAddress(), historicOfferSummary.getMode());
					}
				}
			}

		} catch (DataException e) {
			session.close(4003, "generic repository issue");
			return;
		}

		if (!sendOfferSummaries(session, crossChainOfferSummaries)) {
			session.close(4004, "websocket issue");
			return;
		}

		BlockNotifier.Listener listener = blockInfo -> onNotify(session, blockInfo, previousAtModes);
		BlockNotifier.getInstance().register(session, listener);
	}

	@OnWebSocketClose
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		BlockNotifier.getInstance().deregister(session);
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		/* ignored */
	}

	private void onNotify(Session session, BlockInfo blockInfo, final Map<String, BTCACCT.Mode> previousAtModes) {
		List<CrossChainOfferSummary> crossChainOfferSummaries = null;

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Find any new trade ATs since this block
			final Boolean isFinished = null;
			final Integer dataByteOffset = null;
			final Long expectedValue = null;
			final Integer minimumFinalHeight = blockInfo.getHeight();

			List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStates(BTCACCT.CODE_BYTES_HASH,
					isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
					null, null, null);

			if (atStates == null)
				return;

			crossChainOfferSummaries = produceSummaries(repository, atStates, blockInfo.getTimestamp());
		} catch (DataException e) {
			// No output this time
		}

		synchronized (previousAtModes) { //NOSONAR squid:S2445 suppressed because previousAtModes is final and curried in lambda
			// Remove any entries unchanged from last time
			crossChainOfferSummaries.removeIf(offerSummary -> previousAtModes.get(offerSummary.getQortalAtAddress()) == offerSummary.getMode());

			// Don't send anything if no results
			if (crossChainOfferSummaries.isEmpty())
				return;

			final boolean wasSent = sendOfferSummaries(session, crossChainOfferSummaries);

			if (!wasSent)
				return;

			previousAtModes.putAll(crossChainOfferSummaries.stream().collect(Collectors.toMap(CrossChainOfferSummary::getQortalAtAddress, CrossChainOfferSummary::getMode)));
		}
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
