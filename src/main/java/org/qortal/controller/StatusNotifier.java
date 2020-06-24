package org.qortal.controller;

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

	public synchronized void register(Session session, Listener listener) {
		this.listenersBySession.put(session, listener);
	}

	public synchronized void deregister(Session session) {
		this.listenersBySession.remove(session);
	}

	public synchronized void onStatusChange(long now) {
		for (Listener listener : this.listenersBySession.values())
			listener.notify(now);
	}

}
