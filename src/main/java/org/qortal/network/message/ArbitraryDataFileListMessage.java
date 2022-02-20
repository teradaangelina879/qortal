package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.network.PeerData;
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
	private static final int MAX_PEER_ADDRESS_LENGTH = PeerData.MAX_PEER_ADDRESS_SIZE;

	private final byte[] signature;
	private final List<byte[]> hashes;
	private Long requestTime;
	private Integer requestHops;
	private String peerAddress;
	private Boolean isRelayPossible;


	public ArbitraryDataFileListMessage(byte[] signature, List<byte[]> hashes, Long requestTime,
										Integer requestHops, String peerAddress, boolean isRelayPossible) {
		super(MessageType.ARBITRARY_DATA_FILE_LIST);

		this.signature = signature;
		this.hashes = hashes;
		this.requestTime = requestTime;
		this.requestHops = requestHops;
		this.peerAddress = peerAddress;
		this.isRelayPossible = isRelayPossible;
	}

	public ArbitraryDataFileListMessage(int id, byte[] signature, List<byte[]> hashes, Long requestTime,
										Integer requestHops, String peerAddress, boolean isRelayPossible) {
		super(id, MessageType.ARBITRARY_DATA_FILE_LIST);

		this.signature = signature;
		this.hashes = hashes;
		this.requestTime = requestTime;
		this.requestHops = requestHops;
		this.peerAddress = peerAddress;
		this.isRelayPossible = isRelayPossible;
	}

	public List<byte[]> getHashes() {
		return this.hashes;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException, TransformationException {
		byte[] signature = new byte[SIGNATURE_LENGTH];
		bytes.get(signature);

		int count = bytes.getInt();

		List<byte[]> hashes = new ArrayList<>();
		for (int i = 0; i < count; ++i) {

			byte[] hash = new byte[HASH_LENGTH];
			bytes.get(hash);
			hashes.add(hash);
		}

		Long requestTime = null;
		Integer requestHops = null;
		String peerAddress = null;
		boolean isRelayPossible = true; // Legacy versions only send this message when relaying is possible

		// The remaining fields are optional

		if (bytes.hasRemaining()) {

			requestTime = bytes.getLong();

			requestHops = bytes.getInt();

			peerAddress = Serialization.deserializeSizedStringV2(bytes, MAX_PEER_ADDRESS_LENGTH);

			isRelayPossible = bytes.getInt() > 0;

		}

		return new ArbitraryDataFileListMessage(id, signature, hashes, requestTime, requestHops, peerAddress, isRelayPossible);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(this.signature);

			bytes.write(Ints.toByteArray(this.hashes.size()));

			for (byte[] hash : this.hashes) {
				bytes.write(hash);
			}

			if (this.requestTime == null) { // To maintain backwards support
				return bytes.toByteArray();
			}

			// The remaining fields are optional

			bytes.write(Longs.toByteArray(this.requestTime));

			bytes.write(Ints.toByteArray(this.requestHops));

			Serialization.serializeSizedStringV2(bytes, this.peerAddress);

			bytes.write(Ints.toByteArray(this.isRelayPossible ? 1 : 0));

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

	public ArbitraryDataFileListMessage cloneWithNewId(int newId) {
		ArbitraryDataFileListMessage clone = new ArbitraryDataFileListMessage(this.signature, this.hashes,
				this.requestTime, this.requestHops, this.peerAddress, this.isRelayPossible);
		clone.setId(newId);
		return clone;
	}

	public void removeOptionalStats() {
		this.requestTime = null;
		this.requestHops = null;
		this.peerAddress = null;
		this.isRelayPossible = null;
	}

	public Long getRequestTime() {
		return this.requestTime;
	}

	public void setRequestTime(Long requestTime) {
		this.requestTime = requestTime;
	}

	public Integer getRequestHops() {
		return this.requestHops;
	}

	public void setRequestHops(Integer requestHops) {
		this.requestHops = requestHops;
	}

	public String getPeerAddress() {
		return this.peerAddress;
	}

	public void setPeerAddress(String peerAddress) {
		this.peerAddress = peerAddress;
	}

	public Boolean isRelayPossible() {
		return this.isRelayPossible;
	}

	public void setIsRelayPossible(Boolean isRelayPossible) {
		this.isRelayPossible = isRelayPossible;
	}

}
