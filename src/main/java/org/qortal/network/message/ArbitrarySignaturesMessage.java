package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ArbitrarySignaturesMessage extends Message {

	private static final int SIGNATURE_LENGTH = Transformer.SIGNATURE_LENGTH;

	private List<byte[]> signatures;

	public ArbitrarySignaturesMessage(List<byte[]> signatures) {
		this(-1, signatures);
	}

	private ArbitrarySignaturesMessage(int id, List<byte[]> signatures) {
		super(id, MessageType.ARBITRARY_SIGNATURES);

		this.signatures = signatures;
	}

	public List<byte[]> getSignatures() {
		return this.signatures;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		int count = bytes.getInt();

		if (bytes.remaining() != count * SIGNATURE_LENGTH)
			return null;

		List<byte[]> signatures = new ArrayList<>();
		for (int i = 0; i < count; ++i) {
			byte[] signature = new byte[SIGNATURE_LENGTH];
			bytes.get(signature);
			signatures.add(signature);
		}

		return new ArbitrarySignaturesMessage(id, signatures);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(this.signatures.size()));

			for (byte[] signature : this.signatures)
				bytes.write(signature);

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
