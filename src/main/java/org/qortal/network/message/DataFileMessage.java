package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.storage.ArbitraryDataFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class DataFileMessage extends Message {
	
	private final ArbitraryDataFile arbitraryDataFile;

	public DataFileMessage(ArbitraryDataFile arbitraryDataFile) {
		super(MessageType.DATA_FILE);

		this.arbitraryDataFile = arbitraryDataFile;
	}

	public DataFileMessage(int id, ArbitraryDataFile arbitraryDataFile) {
		super(id, MessageType.DATA_FILE);

		this.arbitraryDataFile = arbitraryDataFile;
	}

	public ArbitraryDataFile getArbitraryDataFile() {
		return this.arbitraryDataFile;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws UnsupportedEncodingException {
		int dataLength = byteBuffer.getInt();

		if (byteBuffer.remaining() != dataLength)
			return null;

		byte[] data = new byte[dataLength];
		byteBuffer.get(data);
		ArbitraryDataFile arbitraryDataFile = new ArbitraryDataFile(data);

		return new DataFileMessage(id, arbitraryDataFile);
	}

	@Override
	protected byte[] toData() {
		if (this.arbitraryDataFile == null) {
			return null;
		}

		byte[] data = this.arbitraryDataFile.getBytes();
		if (data == null) {
			return null;
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(data.length));

			bytes.write(data);

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

	public DataFileMessage cloneWithNewId(int newId) {
		DataFileMessage clone = new DataFileMessage(this.arbitraryDataFile);
		clone.setId(newId);
		return clone;
	}

}
