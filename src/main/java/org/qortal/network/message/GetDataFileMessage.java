package org.qortal.network.message;

import org.qortal.transform.transaction.TransactionTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class GetDataFileMessage extends Message {

	private static final int HASH_LENGTH = TransactionTransformer.SHA256_LENGTH;

	private final byte[] hash;

	public GetDataFileMessage(byte[] hash) {
		this(-1, hash);
	}

	private GetDataFileMessage(int id, byte[] hash) {
		super(id, MessageType.GET_DATA_FILE);

		this.hash = hash;
	}

	public byte[] getHash() {
		return this.hash;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		if (bytes.remaining() != HASH_LENGTH)
			return null;

		byte[] hash = new byte[HASH_LENGTH];

		bytes.get(hash);

		return new GetDataFileMessage(id, hash);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(this.hash);

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
