package org.qortal.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.websocket.api.Session;

public class StatusNotifier {

	private static StatusNotifier instance;

	@FunctionalInterface
	public interface Listener {
		void notify(long timestamp);
	}

	private Map<Session, Listener> listenersBySession = new HashMap<>();

	private StatusNotifier() {
	}

	public static synchronized StatusNotifier getInstance() {
		if (instance == null)
			instance = new StatusNotifier();

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

	public void onStatusChange(long now) {
		for (Listener listener : getAllListeners())
			listener.notify(now);
	}

	private Collection<Listener> getAllListeners() {
		// Make a copy of listeners to both avoid concurrent modification
		// and reduce synchronization time
		synchronized (this.listenersBySession) {
			return new ArrayList<>(this.listenersBySession.values());
		}
	}

}
