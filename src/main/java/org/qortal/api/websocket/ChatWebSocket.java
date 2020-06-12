package org.qortal.api.websocket;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.controller.ChatNotifier;
import org.qortal.crypto.Crypto;
import org.qortal.data.chat.ActiveChats;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

@WebSocket
@SuppressWarnings("serial")
public class ChatWebSocket extends WebSocketServlet implements ApiWebSocket {

	@Override
	public void configure(WebSocketServletFactory factory) {
		factory.register(ChatWebSocket.class);
	}

	@OnWebSocketConnect
	public void onWebSocketConnect(Session session) {
		Map<String, String> pathParams = this.getPathParams(session, "/{address}");
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();

		String address = pathParams.get("address");
		if (address == null || !Crypto.isValidAddress(address)) {
			session.close(4001, "invalid address");
			return;
		}

		ChatNotifier.Listener listener = matchingAddress -> onNotify(session, matchingAddress);
		ChatNotifier.getInstance().register(address, listener);

		this.onNotify(session, address);
	}

	@OnWebSocketClose
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		Map<String, String> pathParams = this.getPathParams(session, "/{address}");
		String address = pathParams.get("address");
		ChatNotifier.getInstance().deregister(address);
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
	}

	@Override
	public void onNotify(Session session, String address) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ActiveChats activeChats = repository.getChatRepository().getActiveChats(address);

			StringWriter stringWriter = new StringWriter();

			this.marshall(stringWriter, activeChats);

			session.getRemote().sendString(stringWriter.toString());
		} catch (DataException | IOException e) {
			// No output this time?
		}
	}

}
