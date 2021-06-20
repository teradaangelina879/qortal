package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.storage.DataFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class DataFileMessage extends Message {
	
	private final DataFile dataFile;

	public DataFileMessage(DataFile dataFile) {
		super(MessageType.DATA_FILE);

		this.dataFile = dataFile;
	}

	public DataFileMessage(int id, DataFile dataFile) {
		super(id, MessageType.DATA_FILE);

		this.dataFile = dataFile;
	}

	public DataFile getDataFile() {
		return this.dataFile;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws UnsupportedEncodingException {
		int dataLength = byteBuffer.getInt();

		if (byteBuffer.remaining() != dataLength)
			return null;

		byte[] data = new byte[dataLength];
		byteBuffer.get(data);
		DataFile dataFile = new DataFile(data);

		return new DataFileMessage(id, dataFile);
	}

	@Override
	protected byte[] toData() {
		if (this.dataFile == null) {
			return null;
		}

		byte[] data = this.dataFile.getBytes();
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
		DataFileMessage clone = new DataFileMessage(this.dataFile);
		clone.setId(newId);
		return clone;
	}

}
