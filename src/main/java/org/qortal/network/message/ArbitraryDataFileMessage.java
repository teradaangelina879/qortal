package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.repository.DataException;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class ArbitraryDataFileMessage extends Message {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileMessage.class);

	private final byte[] signature;
	private final ArbitraryDataFile arbitraryDataFile;

	public ArbitraryDataFileMessage(byte[] signature, ArbitraryDataFile arbitraryDataFile) {
		super(MessageType.ARBITRARY_DATA_FILE);

		this.signature = signature;
		this.arbitraryDataFile = arbitraryDataFile;
	}

	public ArbitraryDataFileMessage(int id, byte[] signature, ArbitraryDataFile arbitraryDataFile) {
		super(id, MessageType.ARBITRARY_DATA_FILE);

		this.signature = signature;
		this.arbitraryDataFile = arbitraryDataFile;
	}

	public ArbitraryDataFile getArbitraryDataFile() {
		return this.arbitraryDataFile;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		int dataLength = byteBuffer.getInt();

		if (byteBuffer.remaining() < dataLength)
			throw new BufferUnderflowException();

		byte[] data = new byte[dataLength];
		byteBuffer.get(data);

		try {
			ArbitraryDataFile arbitraryDataFile = new ArbitraryDataFile(data, signature);
			return new ArbitraryDataFileMessage(id, signature, arbitraryDataFile);
		} catch (DataException e) {
			LOGGER.info("Unable to process received file: {}", e.getMessage());
			throw new MessageException("Unable to process received file: " + e.getMessage(), e);
		}
	}

	@Override
	protected byte[] toData() throws IOException {
		if (this.arbitraryDataFile == null) {
			return null;
		}

		byte[] data = this.arbitraryDataFile.getBytes();
		if (data == null) {
			return null;
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		bytes.write(signature);

		bytes.write(Ints.toByteArray(data.length));

		bytes.write(data);

		return bytes.toByteArray();
	}

	public ArbitraryDataFileMessage cloneWithNewId(int newId) {
		ArbitraryDataFileMessage clone = new ArbitraryDataFileMessage(this.signature, this.arbitraryDataFile);
		clone.setId(newId);
		return clone;
	}

}
