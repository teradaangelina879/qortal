package org.qortal.controller;

import java.util.ArrayList;
import java.util.Collection;
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

	public void register(Session session, Listener listener) {
		synchronized (this.listenersBySession) {
			this.listenersBySession.put(session, listener);
		}
	}

	public void deregister(Session session) {
		synchronized (this.listenersBySession) {
			this.listenersBySession.remove(session);
		}
	}

	public void onNewBlock(BlockData blockData) {
		for (Listener listener : getAllListeners())
			listener.notify(blockData);
	}

	private Collection<Listener> getAllListeners() {
		// Make a copy of listeners to both avoid concurrent modification
		// and reduce synchronization time
		synchronized (this.listenersBySession) {
			return new ArrayList<>(this.listenersBySession.values());
		}
	}

}
