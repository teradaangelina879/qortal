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
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.api.model.CrossChainOfferSummary;
import org.qortal.controller.BlockNotifier;
import org.qortal.crosschain.BTCACCT;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.NTP;

@WebSocket
@SuppressWarnings("serial")
public class TradeOffersWebSocket extends WebSocketServlet implements ApiWebSocket {

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

			if (includeHistoric) {
				// We also want REDEEMED trades over the last 24 hours
				long timestamp = NTP.getTime() - 24 * 60 * 60 * 1000L;
				minimumFinalHeight = repository.getBlockRepository().getHeightFromTimestamp(timestamp);

				if (minimumFinalHeight != 0) {
					isFinished = Boolean.TRUE;
					expectedValue = (long) BTCACCT.Mode.REDEEMED.value;
					++minimumFinalHeight; // because height is just *before* timestamp

					List<ATStateData> historicAtStates = repository.getATRepository().getMatchingFinalATStates(BTCACCT.CODE_BYTES_HASH,
							isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
							null, null, null);

					if (historicAtStates == null) {
						session.close(4002, "repository issue fetching REDEEMED trades");
						return;
					}

					initialAtStates.addAll(historicAtStates);

					// Save initial AT modes
					previousAtModes.putAll(historicAtStates.stream().collect(Collectors.toMap(ATStateData::getATAddress, atState -> BTCACCT.Mode.REDEEMED)));
				}
			}

			crossChainOfferSummaries = produceSummaries(repository, initialAtStates);
		} catch (DataException e) {
			session.close(4003, "generic repository issue");
			return;
		}

		if (!sendOfferSummaries(session, crossChainOfferSummaries)) {
			session.close(4004, "websocket issue");
			return;
		}

		BlockNotifier.Listener listener = blockData -> onNotify(session, blockData, previousAtModes);
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

	private void onNotify(Session session, BlockData blockData, final Map<String, BTCACCT.Mode> previousAtModes) {
		synchronized (previousAtModes) { //NOSONAR squid:S2445 suppressed because previousAtModes is final and curried in lambda
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

				List<CrossChainOfferSummary> crossChainOfferSummaries = produceSummaries(repository, atStates);

				// Remove any entries unchanged from last time
				crossChainOfferSummaries.removeIf(offerSummary -> previousAtModes.get(offerSummary.getQortalAtAddress()) == offerSummary.getMode());

				// Don't send anything if no results
				if (crossChainOfferSummaries.isEmpty())
					return;

				final boolean wasSent = sendOfferSummaries(session, crossChainOfferSummaries);

				if (!wasSent)
					return;

				previousAtModes.putAll(crossChainOfferSummaries.stream().collect(Collectors.toMap(CrossChainOfferSummary::getQortalAtAddress, CrossChainOfferSummary::getMode)));
			} catch (DataException e) {
				// No output this time
			}
		}
	}

	private boolean sendOfferSummaries(Session session, List<CrossChainOfferSummary> crossChainOfferSummaries) {
		try {
			StringWriter stringWriter = new StringWriter();
			this.marshall(stringWriter, crossChainOfferSummaries);

			String output = stringWriter.toString();
			session.getRemote().sendStringByFuture(output);
		} catch (IOException e) {
			// No output this time?
			return false;
		}

		return true;
	}

	private static List<CrossChainOfferSummary> produceSummaries(Repository repository, List<ATStateData> atStates) throws DataException {
		List<CrossChainOfferSummary> offerSummaries = new ArrayList<>();

		for (ATStateData atState : atStates)
			offerSummaries.add(new CrossChainOfferSummary(BTCACCT.populateTradeData(repository, atState)));

		return offerSummaries;
	}

}
