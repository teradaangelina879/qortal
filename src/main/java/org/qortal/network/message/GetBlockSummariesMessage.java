package org.qortal.network.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.qortal.transform.block.BlockTransformer;

import com.google.common.primitives.Ints;

public class GetBlockSummariesMessage extends Message {

	private final byte[] parentSignature;
	private final int numberRequested;

	public GetBlockSummariesMessage(byte[] parentSignature, int numberRequested) {
		this(-1, parentSignature, numberRequested);
	}

	private GetBlockSummariesMessage(int id, byte[] parentSignature, int numberRequested) {
		super(id, MessageType.GET_BLOCK_SUMMARIES);

		this.parentSignature = parentSignature;
		this.numberRequested = numberRequested;
	}

	public byte[] getParentSignature() {
		return this.parentSignature;
	}

	public int getNumberRequested() {
		return this.numberRequested;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		byte[] parentSignature = new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH];
		bytes.get(parentSignature);

		int numberRequested = bytes.getInt();

		return new GetBlockSummariesMessage(id, parentSignature, numberRequested);
	}

	@Override
	protected byte[] toData() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		bytes.write(this.parentSignature);

		bytes.write(Ints.toByteArray(this.numberRequested));

		return bytes.toByteArray();
	}

}
