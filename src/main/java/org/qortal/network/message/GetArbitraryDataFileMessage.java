package org.qortal.network.message;

import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class GetArbitraryDataFileMessage extends Message {

	private final byte[] signature;
	private final byte[] hash;

	public GetArbitraryDataFileMessage(byte[] signature, byte[] hash) {
		this(-1, signature, hash);
	}

	private GetArbitraryDataFileMessage(int id, byte[] signature, byte[] hash) {
		super(id, MessageType.GET_ARBITRARY_DATA_FILE);

		this.signature = signature;
		this.hash = hash;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public byte[] getHash() {
		return this.hash;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		bytes.get(signature);

		byte[] hash = new byte[Transformer.SHA256_LENGTH];
		bytes.get(hash);

		return new GetArbitraryDataFileMessage(id, signature, hash);
	}

	@Override
	protected byte[] toData() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		bytes.write(this.signature);

		bytes.write(this.hash);

		return bytes.toByteArray();
	}

}
