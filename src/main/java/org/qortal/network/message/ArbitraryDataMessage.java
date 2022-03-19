package org.qortal.network.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import org.qortal.transform.Transformer;

import com.google.common.primitives.Ints;

public class ArbitraryDataMessage extends Message {

	private final byte[] signature;
	private final byte[] data;

	public ArbitraryDataMessage(byte[] signature, byte[] data) {
		this(-1, signature, data);
	}

	private ArbitraryDataMessage(int id, byte[] signature, byte[] data) {
		super(id, MessageType.ARBITRARY_DATA);

		this.signature = signature;
		this.data = data;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public byte[] getData() {
		return this.data;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		int dataLength = byteBuffer.getInt();

		if (byteBuffer.remaining() < dataLength)
			throw new BufferUnderflowException();

		byte[] data = new byte[dataLength];
		byteBuffer.get(data);

		return new ArbitraryDataMessage(id, signature, data);
	}

	@Override
	protected byte[] toData() throws IOException {
		if (this.data == null)
			return null;

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		bytes.write(this.signature);

		bytes.write(Ints.toByteArray(this.data.length));

		bytes.write(this.data);

		return bytes.toByteArray();
	}

}
