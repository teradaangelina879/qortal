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

	/**
	 * <b>WARNING:</b> before calling this method,
	 * make sure repository holds no locks, e.g. by calling
	 * <tt>repository.discardChanges()</tt>.
	 * <p>
	 * This is because event listeners might open a new
	 * repository session which will deadlock HSQLDB
	 * if it tries to CHECKPOINT.
	 * <p>
	 * The HSQLDB deadlock occurs because the caller's
	 * repository session blocks the CHECKPOINT until
	 * their transaction is closed, yet event listeners
	 * new sessions are blocked until CHECKPOINT is
	 * completed, hence deadlock.
	 */
	public void notify(Event event) {
		List<Listener> clonedListeners;

		synchronized (LISTENERS) {
			clonedListeners = new ArrayList<>(LISTENERS);
		}

		for (Listener listener : clonedListeners)
			listener.listen(event);
	}
}
