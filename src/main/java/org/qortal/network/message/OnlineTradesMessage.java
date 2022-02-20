package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.network.OnlineTradeData;
import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * For sending list of which trades are online to remote peer.
 *
 * Groups of: number of entries, timestamp, then pubkey + sig + AT address for each entry.
 */
public class OnlineTradesMessage extends Message {
	private List<OnlineTradeData> onlineTrades;
	private byte[] cachedData;

	public OnlineTradesMessage(List<OnlineTradeData> onlineTrades) {
		this(-1, onlineTrades);
	}

	private OnlineTradesMessage(int id, List<OnlineTradeData> onlineTrades) {
		super(id, MessageType.ONLINE_TRADES);

		this.onlineTrades = onlineTrades;
	}

	public List<OnlineTradeData> getOnlineTrades() {
		return this.onlineTrades;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		int tradeCount = bytes.getInt();

		List<OnlineTradeData> onlineTrades = new ArrayList<>(tradeCount);

		while (tradeCount > 0) {
			long timestamp = bytes.getLong();

			for (int i = 0; i < tradeCount; ++i) {
				byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
				bytes.get(publicKey);

				byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
				bytes.get(signature);

				byte[] atAddressBytes = new byte[Transformer.ADDRESS_LENGTH];
				bytes.get(atAddressBytes);
				String atAddress = Base58.encode(atAddressBytes);

				onlineTrades.add(new OnlineTradeData(timestamp, publicKey, signature, atAddress));
			}

			if (bytes.hasRemaining()) {
				tradeCount = bytes.getInt();
			} else {
				// we've finished
				tradeCount = 0;
			}
		}

		return new OnlineTradesMessage(id, onlineTrades);
	}

	@Override
	protected synchronized byte[] toData() {
		if (this.cachedData != null)
			return this.cachedData;

		// Shortcut in case we have no online trade entries
		if (this.onlineTrades.isEmpty()) {
			this.cachedData = Ints.toByteArray(0);
			return this.cachedData;
		}

		// How many of each timestamp
		Map<Long, Integer> countByTimestamp = new HashMap<>();

		for (OnlineTradeData onlineTradeData : this.onlineTrades) {
			Long timestamp = onlineTradeData.getTimestamp();
			countByTimestamp.compute(timestamp, (k, v) -> v == null ? 1 : ++v);
		}

		// We should know exactly how many bytes to allocate now
		int byteSize = countByTimestamp.size() * (Transformer.INT_LENGTH + Transformer.TIMESTAMP_LENGTH)
				+ this.onlineTrades.size() * (Transformer.PUBLIC_KEY_LENGTH + Transformer.SIGNATURE_LENGTH + Transformer.ADDRESS_LENGTH);

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(byteSize);

			for (long timestamp : countByTimestamp.keySet()) {
				bytes.write(Ints.toByteArray(countByTimestamp.get(timestamp)));

				bytes.write(Longs.toByteArray(timestamp));

				for (OnlineTradeData onlineTradeData : this.onlineTrades) {
					if (onlineTradeData.getTimestamp() == timestamp) {
						bytes.write(onlineTradeData.getPublicKey());

						bytes.write(onlineTradeData.getSignature());

						bytes.write(Base58.decode(onlineTradeData.getAtAddress()));
					}
				}
			}

			this.cachedData = bytes.toByteArray();
			return this.cachedData;
		} catch (IOException e) {
			return null;
		}
	}

}
