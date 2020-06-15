package org.qortal.controller;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.websocket.api.Session;
import org.qortal.data.block.BlockData;

public class BlockNotifier {

	private static BlockNotifier instance;

	@FunctionalInterface
	public interface Listener {
		void notify(BlockData blockData);
	}

	private Map<Session, Listener> listenersBySession = new HashMap<>();

	private BlockNotifier() {
	}

	public static synchronized BlockNotifier getInstance() {
		if (instance == null)
			instance = new BlockNotifier();

		return instance;
	}

	public synchronized void register(Session session, Listener listener) {
		this.listenersBySession.put(session, listener);
	}

	public synchronized void deregister(Session session) {
		this.listenersBySession.remove(session);
	}

	public synchronized void onNewBlock(BlockData blockData) {
		for (Listener listener : this.listenersBySession.values())
			listener.notify(blockData);
	}

}
