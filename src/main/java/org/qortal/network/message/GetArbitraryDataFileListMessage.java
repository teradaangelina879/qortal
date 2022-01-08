package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import static org.qortal.transform.Transformer.INT_LENGTH;
import static org.qortal.transform.Transformer.LONG_LENGTH;

public class GetArbitraryDataFileListMessage extends Message {

	private static final int SIGNATURE_LENGTH = Transformer.SIGNATURE_LENGTH;

	private final byte[] signature;
	private final long requestTime;
	private int requestHops;

	public GetArbitraryDataFileListMessage(byte[] signature, long requestTime, int requestHops) {
		this(-1, signature, requestTime, requestHops);
	}

	private GetArbitraryDataFileListMessage(int id, byte[] signature, long requestTime, int requestHops) {
		super(id, MessageType.GET_ARBITRARY_DATA_FILE_LIST);

		this.signature = signature;
		this.requestTime = requestTime;
		this.requestHops = requestHops;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		if (bytes.remaining() != SIGNATURE_LENGTH + LONG_LENGTH + INT_LENGTH)
			return null;

		byte[] signature = new byte[SIGNATURE_LENGTH];

		bytes.get(signature);

		long requestTime = bytes.getLong();

		int requestHops = bytes.getInt();

		return new GetArbitraryDataFileListMessage(id, signature, requestTime, requestHops);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(this.signature);

			bytes.write(Longs.toByteArray(this.requestTime));

			bytes.write(Ints.toByteArray(this.requestHops));

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

	public long getRequestTime() {
		return this.requestTime;
	}

	public int getRequestHops() {
		return this.requestHops;
	}
	public void setRequestHops(int requestHops) {
		this.requestHops = requestHops;
	}

}
