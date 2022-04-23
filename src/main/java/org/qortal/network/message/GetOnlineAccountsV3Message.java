package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * For requesting online accounts info from remote peer, given our list of online accounts.
 *
 * Different format to V1 and V2:
 * V1 is: number of entries, then timestamp + pubkey for each entry
 * V2 is: groups of: number of entries, timestamp, then pubkey for each entry
 * V3 is: groups of: timestamp, number of entries (one per leading byte), then hash(pubkeys) for each entry
 */
public class GetOnlineAccountsV3Message extends Message {

	private static final Map<Long, Map<Byte, byte[]>> EMPTY_ONLINE_ACCOUNTS = Collections.emptyMap();
	private Map<Long, Map<Byte, byte[]>> hashesByTimestampThenByte;

	public GetOnlineAccountsV3Message(Map<Long, Map<Byte, byte[]>> hashesByTimestampThenByte) {
		super(MessageType.GET_ONLINE_ACCOUNTS_V3);

		// If we don't have ANY online accounts then it's an easier construction...
		if (hashesByTimestampThenByte.isEmpty()) {
			this.dataBytes = EMPTY_DATA_BYTES;
			return;
		}

		// We should know exactly how many bytes to allocate now
		int byteSize = hashesByTimestampThenByte.size() * (Transformer.TIMESTAMP_LENGTH + Transformer.INT_LENGTH)
				+ Transformer.TIMESTAMP_LENGTH /* trailing zero entry indicates end of entries */;

		byteSize += hashesByTimestampThenByte.values()
				.stream()
				.mapToInt(map -> map.size() * Transformer.PUBLIC_KEY_LENGTH)
				.sum();

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(byteSize);

		// Warning: no double-checking/fetching! We must be ConcurrentMap compatible.
		// So no contains() then get() or multiple get()s on the same key/map.
		try {
			for (var outerMapEntry : hashesByTimestampThenByte.entrySet()) {
				bytes.write(Longs.toByteArray(outerMapEntry.getKey()));

				var innerMap = outerMapEntry.getValue();

				bytes.write(Ints.toByteArray(innerMap.size()));

				for (byte[] hashBytes : innerMap.values()) {
					bytes.write(hashBytes);
				}
			}

			// end of records
			bytes.write(Longs.toByteArray(0L));
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private GetOnlineAccountsV3Message(int id, Map<Long, Map<Byte, byte[]>> hashesByTimestampThenByte) {
		super(id, MessageType.GET_ONLINE_ACCOUNTS_V3);

		this.hashesByTimestampThenByte = hashesByTimestampThenByte;
	}

	public Map<Long, Map<Byte, byte[]>> getHashesByTimestampThenByte() {
		return this.hashesByTimestampThenByte;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		// 'empty' case
		if (!bytes.hasRemaining()) {
			return new GetOnlineAccountsV3Message(id, EMPTY_ONLINE_ACCOUNTS);
		}

		Map<Long, Map<Byte, byte[]>> hashesByTimestampThenByte = new HashMap<>();

		while (true) {
			long timestamp = bytes.getLong();
			if (timestamp == 0)
				// Zero timestamp indicates end of records
				break;

			int hashCount = bytes.getInt();
			Map<Byte, byte[]> hashesByByte = new HashMap<>();

			for (int i = 0; i < hashCount; ++i) {
				byte[] publicKeyHash = new byte[Transformer.PUBLIC_KEY_LENGTH];
				bytes.get(publicKeyHash);

				hashesByByte.put(publicKeyHash[0], publicKeyHash);
			}

			hashesByTimestampThenByte.put(timestamp, hashesByByte);
		}

		return new GetOnlineAccountsV3Message(id, hashesByTimestampThenByte);
	}

}
