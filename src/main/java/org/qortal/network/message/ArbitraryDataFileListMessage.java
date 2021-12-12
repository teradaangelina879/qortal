package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ArbitraryDataFileListMessage extends Message {

	private static final int SIGNATURE_LENGTH = Transformer.SIGNATURE_LENGTH;
	private static final int HASH_LENGTH = Transformer.SHA256_LENGTH;

	private final byte[] signature;
	private final List<byte[]> hashes;
	private String peerAddress;

	public ArbitraryDataFileListMessage(byte[] signature, String peerAddress, List<byte[]> hashes) {
		super(MessageType.ARBITRARY_DATA_FILE_LIST);

		this.signature = signature;
		this.peerAddress = peerAddress;
		this.hashes = hashes;
	}

	public ArbitraryDataFileListMessage(int id, byte[] signature, String peerAddress, List<byte[]> hashes) {
		super(id, MessageType.ARBITRARY_DATA_FILE_LIST);

		this.signature = signature;
		this.peerAddress = peerAddress;
		this.hashes = hashes;
	}

	public List<byte[]> getHashes() {
		return this.hashes;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public void setPeerAddress(String peerAddress) {
		this.peerAddress = peerAddress;
	}

	public String getPeerAddress() {
		return this.peerAddress;
	}


	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException, TransformationException {
		byte[] signature = new byte[SIGNATURE_LENGTH];
		bytes.get(signature);

		String peerAddress = Serialization.deserializeSizedString(bytes, 255);

		int count = bytes.getInt();

		if (bytes.remaining() != count * HASH_LENGTH)
			return null;

		List<byte[]> hashes = new ArrayList<>();
		for (int i = 0; i < count; ++i) {

			byte[] hash = new byte[HASH_LENGTH];
			bytes.get(hash);
			hashes.add(hash);
		}

		return new ArbitraryDataFileListMessage(id, signature, peerAddress, hashes);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(this.signature);

			Serialization.serializeSizedString(bytes, this.peerAddress);

			bytes.write(Ints.toByteArray(this.hashes.size()));

			for (byte[] hash : this.hashes) {
				bytes.write(hash);
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

	public ArbitraryDataFileListMessage cloneWithNewId(int newId) {
		ArbitraryDataFileListMessage clone = new ArbitraryDataFileListMessage(this.signature, this.peerAddress, this.hashes);
		clone.setId(newId);
		return clone;
	}

}
