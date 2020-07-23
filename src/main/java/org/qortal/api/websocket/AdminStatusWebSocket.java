package org.qortal.api.websocket;

import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.api.model.NodeStatus;
import org.qortal.controller.StatusNotifier;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

@WebSocket
@SuppressWarnings("serial")
public class AdminStatusWebSocket extends WebSocketServlet implements ApiWebSocket {

	@Override
	public void configure(WebSocketServletFactory factory) {
		factory.register(AdminStatusWebSocket.class);
	}

	@OnWebSocketConnect
	public void onWebSocketConnect(Session session) {
		AtomicReference<String> previousOutput = new AtomicReference<>(null);

		StatusNotifier.Listener listener = timestamp -> onNotify(session, previousOutput);
		StatusNotifier.getInstance().register(session, listener);

		this.onNotify(session, previousOutput);
	}

	@OnWebSocketClose
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		StatusNotifier.getInstance().deregister(session);
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
	}

	private void onNotify(Session session,AtomicReference<String> previousOutput) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			NodeStatus nodeStatus = new NodeStatus();

			StringWriter stringWriter = new StringWriter();

			this.marshall(stringWriter, nodeStatus);

			// Only output if something has changed
			String output = stringWriter.toString();
			if (output.equals(previousOutput.get()))
				return;

			previousOutput.set(output);
			session.getRemote().sendStringByFuture(output);
		} catch (DataException | IOException | WebSocketException e) {
			// No output this time?
		}
	}

}
