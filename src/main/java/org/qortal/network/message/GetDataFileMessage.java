package org.qortal.network.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class GetDataFileMessage extends Message {

	private static final int DIGEST_LENGTH = 32;

	private final byte[] digest;

	public GetDataFileMessage(byte[] digest) {
		this(-1, digest);
	}

	private GetDataFileMessage(int id, byte[] digest) {
		super(id, MessageType.GET_DATA_FILE);

		this.digest = digest;
	}

	public byte[] getDigest() {
		return this.digest;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		if (bytes.remaining() != DIGEST_LENGTH)
			return null;

		byte[] digest = new byte[DIGEST_LENGTH];

		bytes.get(digest);

		return new GetDataFileMessage(id, digest);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(this.digest);

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
