package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.repository.DataException;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class ArbitraryMetadataMessage extends Message {

	private static final int SIGNATURE_LENGTH = Transformer.SIGNATURE_LENGTH;

	private final byte[] signature;
	private final ArbitraryDataFile arbitraryMetadataFile;

	public ArbitraryMetadataMessage(byte[] signature, ArbitraryDataFile arbitraryDataFile) {
		super(MessageType.ARBITRARY_METADATA);

		this.signature = signature;
		this.arbitraryMetadataFile = arbitraryDataFile;
	}

	public ArbitraryMetadataMessage(int id, byte[] signature, ArbitraryDataFile arbitraryDataFile) {
		super(id, MessageType.ARBITRARY_METADATA);

		this.signature = signature;
		this.arbitraryMetadataFile = arbitraryDataFile;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public ArbitraryDataFile getArbitraryMetadataFile() {
		return this.arbitraryMetadataFile;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws UnsupportedEncodingException {
		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		int dataLength = byteBuffer.getInt();

		if (byteBuffer.remaining() != dataLength)
			return null;

		byte[] data = new byte[dataLength];
		byteBuffer.get(data);

		try {
			ArbitraryDataFile arbitraryMetadataFile = new ArbitraryDataFile(data, signature);
			return new ArbitraryMetadataMessage(id, signature, arbitraryMetadataFile);
		}
		catch (DataException e) {
			return null;
		}
	}

	@Override
	protected byte[] toData() {
		if (this.arbitraryMetadataFile == null) {
			return null;
		}

		byte[] data = this.arbitraryMetadataFile.getBytes();
		if (data == null) {
			return null;
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(signature);

			bytes.write(Ints.toByteArray(data.length));

			bytes.write(data);

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

	public ArbitraryMetadataMessage cloneWithNewId(int newId) {
		ArbitraryMetadataMessage clone = new ArbitraryMetadataMessage(this.signature, this.arbitraryMetadataFile);
		clone.setId(newId);
		return clone;
	}

}
