package org.qortal.network.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.qortal.transform.Transformer;

public class GetArbitraryDataMessage extends Message {

	private final byte[] signature;

	public GetArbitraryDataMessage(byte[] signature) {
		this(-1, signature);
	}

	private GetArbitraryDataMessage(int id, byte[] signature) {
		super(id, MessageType.GET_ARBITRARY_DATA);

		this.signature = signature;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];

		bytes.get(signature);

		return new GetArbitraryDataMessage(id, signature);
	}

	@Override
	protected byte[] toData() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		bytes.write(this.signature);

		return bytes.toByteArray();
	}

}
