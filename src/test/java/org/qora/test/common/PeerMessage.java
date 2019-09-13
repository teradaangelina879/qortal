package org.qora.test.common;

import org.qora.network.message.Message;
import org.qora.test.common.FakePeer;

public class PeerMessage {
	public final FakePeer peer;
	public final Message message;
	public final long sentWhen;
	public Long processedWhen = null;

	public PeerMessage(FakePeer peer, Message message) {
		this.peer = peer;
		this.message = message;
		this.sentWhen = System.currentTimeMillis();
	}
}
