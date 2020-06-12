package org.qortal.controller;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.websocket.api.Session;
import org.qortal.data.transaction.ChatTransactionData;

public class ChatNotifier {

	private static ChatNotifier instance;

	@FunctionalInterface
	public interface Listener {
		void notify(ChatTransactionData chatTransactionData);
	}

	private Map<Session, Listener> listenersBySession = new HashMap<>();

	private ChatNotifier() {
	}

	public static synchronized ChatNotifier getInstance() {
		if (instance == null)
			instance = new ChatNotifier();

		return instance;
	}

	public synchronized void register(Session session, Listener listener) {
		this.listenersBySession.put(session, listener);
	}

	public synchronized void deregister(Session session) {
		this.listenersBySession.remove(session);
	}

	public synchronized void onNewChatTransaction(ChatTransactionData chatTransactionData) {
		for (Listener listener : this.listenersBySession.values())
			listener.notify(chatTransactionData);
	}

}
