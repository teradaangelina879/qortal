package org.qortal.network.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.qortal.transform.block.BlockTransformer;

public class GetBlockMessage extends Message {

	private final byte[] signature;

	public GetBlockMessage(byte[] signature) {
		this(-1, signature);
	}

	private GetBlockMessage(int id, byte[] signature) {
		super(id, MessageType.GET_BLOCK);

		this.signature = signature;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		byte[] signature = new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH];
		bytes.get(signature);

		return new GetBlockMessage(id, signature);
	}

	@Override
	protected byte[] toData() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		bytes.write(this.signature);

		return bytes.toByteArray();
	}

}
