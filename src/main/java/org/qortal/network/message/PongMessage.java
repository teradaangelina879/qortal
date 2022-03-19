package org.qortal.network.message;

import java.nio.ByteBuffer;

public class PongMessage extends Message {

	public PongMessage() {
		this(-1);
	}

	private PongMessage(int id) {
		super(id, MessageType.PONG);
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		return new PongMessage(id);
	}

	@Override
	protected byte[] toData() {
		return new byte[0];
	}

}
