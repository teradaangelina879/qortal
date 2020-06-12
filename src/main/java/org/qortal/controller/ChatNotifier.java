package org.qortal.controller;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.qortal.data.transaction.ChatTransactionData;

public class ChatNotifier {

	private static ChatNotifier instance;

	@FunctionalInterface
	public interface Listener {
		void notify(String address);
	}

	private Map<String, Set<Listener>> listenersByAddress = new HashMap<>();

	private ChatNotifier() {
	}

	public static synchronized ChatNotifier getInstance() {
		if (instance == null)
			instance = new ChatNotifier();
		return instance;
	}

	public void register(String address, Listener listener) {
		this.listenersByAddress.computeIfAbsent(address, k -> new HashSet<Listener>()).add(listener);
	}

	public void deregister(String address) {
		this.listenersByAddress.remove(address);
	}

	private void notifyListeners(String address) {
		Set<Listener> listeners = this.listenersByAddress.get(address);
		if (listeners == null)
			return;

		for (Listener listener : listeners)
			listener.notify(address);
	}

	public void onNewChatTransaction(ChatTransactionData chatTransactionData) {
		this.notifyListeners(chatTransactionData.getSender());

		if (chatTransactionData.getRecipient() != null)
			this.notifyListeners(chatTransactionData.getRecipient());
	}

}
