package org.qortal.api.websocket;

import java.io.IOException;
import java.io.StringWriter;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.api.ApiError;
import org.qortal.controller.BlockNotifier;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.Base58;

@WebSocket
@SuppressWarnings("serial")
public class BlocksWebSocket extends ApiWebSocket {

	@Override
	public void configure(WebSocketServletFactory factory) {
		factory.register(BlocksWebSocket.class);
	}

	@OnWebSocketConnect
	public void onWebSocketConnect(Session session) {
		BlockNotifier.Listener listener = blockData -> onNotify(session, blockData);
		BlockNotifier.getInstance().register(session, listener);
	}

	@OnWebSocketClose
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		BlockNotifier.getInstance().deregister(session);
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		// We're expecting either a base58 block signature or an integer block height
		if (message.length() > 128) {
			// Try base58 block signature
			byte[] signature;

			try {
				signature = Base58.decode(message);
			} catch (NumberFormatException e) {
				sendError(session, ApiError.INVALID_SIGNATURE);
				return;
			}

			try (final Repository repository = RepositoryManager.getRepository()) {
				BlockData blockData = repository.getBlockRepository().fromSignature(signature);
				if (blockData == null) {
					sendError(session, ApiError.BLOCK_UNKNOWN);
					return;
				}

				onNotify(session, blockData);
			} catch (DataException e) {
				sendError(session, ApiError.REPOSITORY_ISSUE);
			}

			return;
		}

		if (message.length() > 10)
			// Bigger than max integer value, so probably a ping - silently ignore
			return;

		// Try integer
		int height;

		try {
			height = Integer.parseInt(message);
		} catch (NumberFormatException e) {
			sendError(session, ApiError.INVALID_HEIGHT);
			return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockData blockData = repository.getBlockRepository().fromHeight(height);
			if (blockData == null) {
				sendError(session, ApiError.BLOCK_UNKNOWN);
				return;
			}

			onNotify(session, blockData);
		} catch (DataException e) {
			sendError(session, ApiError.REPOSITORY_ISSUE);
		}
	}

	private void onNotify(Session session, BlockData blockData) {
		StringWriter stringWriter = new StringWriter();

		try {
			marshall(stringWriter, blockData);

			session.getRemote().sendString(stringWriter.toString());
		} catch (IOException e) {
			// No output this time
		}
	}

}
