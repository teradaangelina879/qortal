package org.qortal.network.message;

import org.qortal.storage.DataFile;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class DataFileMessage extends Message {
	
	private final DataFile dataFile;

	public DataFileMessage(DataFile dataFile) {
		super(MessageType.DATA_FILE);

		this.dataFile = dataFile;
	}

	public DataFile getDataFile() {
		return this.dataFile;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws UnsupportedEncodingException {
		byte[] bytes = new byte[byteBuffer.remaining()];
		byteBuffer.get(bytes);
		DataFile dataFile = new DataFile(bytes);

		return new DataFileMessage(dataFile);
	}

	@Override
	protected byte[] toData() {
		if (this.dataFile == null)
			return null;

		return this.dataFile.getBytes();
	}

	public DataFileMessage cloneWithNewId(int newId) {
		DataFileMessage clone = new DataFileMessage(this.dataFile);
		clone.setId(newId);
		return clone;
	}

}
