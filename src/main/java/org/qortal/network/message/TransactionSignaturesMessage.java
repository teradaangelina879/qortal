package org.qortal.network.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.qortal.transform.Transformer;

import com.google.common.primitives.Ints;

public class TransactionSignaturesMessage extends Message {

	private final List<byte[]> signatures;

	public TransactionSignaturesMessage(List<byte[]> signatures) {
		this(-1, signatures);
	}

	private TransactionSignaturesMessage(int id, List<byte[]> signatures) {
		super(id, MessageType.TRANSACTION_SIGNATURES);

		this.signatures = signatures;
	}

	public List<byte[]> getSignatures() {
		return this.signatures;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		int count = bytes.getInt();

		if (bytes.remaining() < count * Transformer.SIGNATURE_LENGTH)
			throw new BufferUnderflowException();

		List<byte[]> signatures = new ArrayList<>();
		for (int i = 0; i < count; ++i) {
			byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
			bytes.get(signature);
			signatures.add(signature);
		}

		return new TransactionSignaturesMessage(id, signatures);
	}

	@Override
	protected byte[] toData() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		bytes.write(Ints.toByteArray(this.signatures.size()));

		for (byte[] signature : this.signatures)
			bytes.write(signature);

		return bytes.toByteArray();
	}

}
