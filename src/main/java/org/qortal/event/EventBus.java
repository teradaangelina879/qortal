package org.qortal.event;

import java.util.ArrayList;
import java.util.List;

public enum EventBus {
	INSTANCE;

	private static final List<Listener> LISTENERS = new ArrayList<>();

	public void addListener(Listener newListener) {
		synchronized (LISTENERS) {
			LISTENERS.add(newListener);
		}
	}

	public void removeListener(Listener listener) {
		synchronized (LISTENERS) {
			LISTENERS.remove(listener);
		}
	}

	public void notify(Event event) {
		List<Listener> clonedListeners;

		synchronized (LISTENERS) {
			clonedListeners = new ArrayList<>(LISTENERS);
		}

		for (Listener listener : clonedListeners)
			listener.listen(event);
	}
}
