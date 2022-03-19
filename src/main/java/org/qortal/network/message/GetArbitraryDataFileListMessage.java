package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.network.PeerData;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class GetArbitraryDataFileListMessage extends Message {

	private final byte[] signature;
	private List<byte[]> hashes;
	private final long requestTime;
	private int requestHops;
	private String requestingPeer;

	public GetArbitraryDataFileListMessage(byte[] signature, List<byte[]> hashes, long requestTime, int requestHops, String requestingPeer) {
		this(-1, signature, hashes, requestTime, requestHops, requestingPeer);
	}

	private GetArbitraryDataFileListMessage(int id, byte[] signature, List<byte[]> hashes, long requestTime, int requestHops, String requestingPeer) {
		super(id, MessageType.GET_ARBITRARY_DATA_FILE_LIST);

		this.signature = signature;
		this.hashes = hashes;
		this.requestTime = requestTime;
		this.requestHops = requestHops;
		this.requestingPeer = requestingPeer;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public List<byte[]> getHashes() {
		return this.hashes;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];

		bytes.get(signature);

		long requestTime = bytes.getLong();

		int requestHops = bytes.getInt();

		List<byte[]> hashes = null;
		if (bytes.hasRemaining()) {
			int hashCount = bytes.getInt();

			hashes = new ArrayList<>();
			for (int i = 0; i < hashCount; ++i) {
				byte[] hash = new byte[Transformer.SHA256_LENGTH];
				bytes.get(hash);
				hashes.add(hash);
			}
		}

		String requestingPeer = null;
		if (bytes.hasRemaining()) {
			try {
				requestingPeer = Serialization.deserializeSizedStringV2(bytes, PeerData.MAX_PEER_ADDRESS_SIZE);
			} catch (TransformationException e) {
				throw new MessageException(e.getMessage(), e);
			}
		}

		return new GetArbitraryDataFileListMessage(id, signature, hashes, requestTime, requestHops, requestingPeer);
	}

	@Override
	protected byte[] toData() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		bytes.write(this.signature);

		bytes.write(Longs.toByteArray(this.requestTime));

		bytes.write(Ints.toByteArray(this.requestHops));

		if (this.hashes != null) {
			bytes.write(Ints.toByteArray(this.hashes.size()));

			for (byte[] hash : this.hashes) {
				bytes.write(hash);
			}
		}
		else {
			bytes.write(Ints.toByteArray(0));
		}

		if (this.requestingPeer != null) {
			Serialization.serializeSizedStringV2(bytes, this.requestingPeer);
		}

		return bytes.toByteArray();
	}

	public long getRequestTime() {
		return this.requestTime;
	}

	public int getRequestHops() {
		return this.requestHops;
	}
	public void setRequestHops(int requestHops) {
		this.requestHops = requestHops;
	}

	public String getRequestingPeer() {
		return this.requestingPeer;
	}

}
