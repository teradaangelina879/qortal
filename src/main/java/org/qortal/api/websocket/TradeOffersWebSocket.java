package org.qortal.api.websocket;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
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
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

@WebSocket
@SuppressWarnings("serial")
public class TradeOffersWebSocket extends WebSocketServlet implements ApiWebSocket {

	@Override
	public void configure(WebSocketServletFactory factory) {
		factory.register(TradeOffersWebSocket.class);
	}

	@OnWebSocketConnect
	public void onWebSocketConnect(Session session) {
		BlockNotifier.Listener listener = blockData -> onNotify(session, blockData);
		BlockNotifier.getInstance().register(session, listener);

		this.onNotify(session, null);
	}

	@OnWebSocketClose
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		BlockNotifier.getInstance().deregister(session);
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
	}

	private void onNotify(Session session, BlockData blockData) {
		List<CrossChainTradeData> crossChainTradeDataList = new ArrayList<>();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Integer minimumFinalHeight;
			if (blockData == null) {
				// If blockData is null then we send all known trade offers
				minimumFinalHeight = null;
			} else {
				// Find any new trade ATs since this block
				minimumFinalHeight = blockData.getHeight();
			}

			final Boolean isFinished = Boolean.FALSE;

			List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStates(BTCACCT.CODE_BYTES_HASH,
					isFinished,
					BTCACCT.MODE_BYTE_OFFSET, (long) BTCACCT.Mode.OFFERING.value,
					minimumFinalHeight,
					null, null, null);

			// Don't send anything if no results and this isn't initial on-connection message
			if (atStates == null || (atStates.isEmpty() && blockData != null))
				return;

			for (ATStateData atState : atStates) {
				CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atState);
				crossChainTradeDataList.add(crossChainTradeData);
			}
		} catch (DataException e) {
			// No output this time?
			return;
		}

		try {
			List<CrossChainOfferSummary> crossChainOffers = crossChainTradeDataList.stream().map(crossChainTradeData -> new CrossChainOfferSummary(crossChainTradeData)).collect(Collectors.toList());

			StringWriter stringWriter = new StringWriter();

			this.marshall(stringWriter, crossChainOffers);

			String output = stringWriter.toString();
			session.getRemote().sendString(output);
		} catch (IOException e) {
			// No output this time?
		}
	}

}
