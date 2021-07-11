package org.qortal.network.message;

import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class GetArbitraryDataFileListMessage extends Message {

	private static final int SIGNATURE_LENGTH = Transformer.SIGNATURE_LENGTH;

	private final byte[] signature;

	public GetArbitraryDataFileListMessage(byte[] signature) {
		this(-1, signature);
	}

	private GetArbitraryDataFileListMessage(int id, byte[] signature) {
		super(id, MessageType.GET_ARBITRARY_DATA_FILE_LIST);

		this.signature = signature;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		if (bytes.remaining() != SIGNATURE_LENGTH)
			return null;

		byte[] signature = new byte[SIGNATURE_LENGTH];

		bytes.get(signature);

		return new GetArbitraryDataFileListMessage(id, signature);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(this.signature);

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
