package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class GetArbitraryMetadataMessage extends Message {

	private final byte[] signature;
	private final long requestTime;
	private int requestHops;

	public GetArbitraryMetadataMessage(byte[] signature, long requestTime, int requestHops) {
		this(-1, signature, requestTime, requestHops);
	}

	private GetArbitraryMetadataMessage(int id, byte[] signature, long requestTime, int requestHops) {
		super(id, MessageType.GET_ARBITRARY_METADATA);

		this.signature = signature;
		this.requestTime = requestTime;
		this.requestHops = requestHops;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		bytes.get(signature);

		long requestTime = bytes.getLong();

		int requestHops = bytes.getInt();

		return new GetArbitraryMetadataMessage(id, signature, requestTime, requestHops);
	}

	@Override
	protected byte[] toData() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		bytes.write(this.signature);

		bytes.write(Longs.toByteArray(this.requestTime));

		bytes.write(Ints.toByteArray(this.requestHops));

		return bytes.toByteArray();
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
