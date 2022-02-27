package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.network.TradePresenceData;
import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * For sending list of trade presences to remote peer.
 *
 * Groups of: number of entries, timestamp, then pubkey + sig + AT address for each entry.
 */
public class TradePresencesMessage extends Message {
	private List<TradePresenceData> tradePresences;
	private byte[] cachedData;

	public TradePresencesMessage(List<TradePresenceData> tradePresences) {
		this(-1, tradePresences);
	}

	private TradePresencesMessage(int id, List<TradePresenceData> tradePresences) {
		super(id, MessageType.TRADE_PRESENCES);

		this.tradePresences = tradePresences;
	}

	public List<TradePresenceData> getTradePresences() {
		return this.tradePresences;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		int groupedEntriesCount = bytes.getInt();

		List<TradePresenceData> tradePresences = new ArrayList<>(groupedEntriesCount);

		while (groupedEntriesCount > 0) {
			long timestamp = bytes.getLong();

			for (int i = 0; i < groupedEntriesCount; ++i) {
				byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
				bytes.get(publicKey);

				byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
				bytes.get(signature);

				byte[] atAddressBytes = new byte[Transformer.ADDRESS_LENGTH];
				bytes.get(atAddressBytes);
				String atAddress = Base58.encode(atAddressBytes);

				tradePresences.add(new TradePresenceData(timestamp, publicKey, signature, atAddress));
			}

			if (bytes.hasRemaining()) {
				groupedEntriesCount = bytes.getInt();
			} else {
				// we've finished
				groupedEntriesCount = 0;
			}
		}

		return new TradePresencesMessage(id, tradePresences);
	}

	@Override
	protected synchronized byte[] toData() {
		if (this.cachedData != null)
			return this.cachedData;

		// Shortcut in case we have no trade presences
		if (this.tradePresences.isEmpty()) {
			this.cachedData = Ints.toByteArray(0);
			return this.cachedData;
		}

		// How many of each timestamp
		Map<Long, Integer> countByTimestamp = new HashMap<>();

		for (TradePresenceData tradePresenceData : this.tradePresences) {
			Long timestamp = tradePresenceData.getTimestamp();
			countByTimestamp.compute(timestamp, (k, v) -> v == null ? 1 : ++v);
		}

		// We should know exactly how many bytes to allocate now
		int byteSize = countByTimestamp.size() * (Transformer.INT_LENGTH + Transformer.TIMESTAMP_LENGTH)
				+ this.tradePresences.size() * (Transformer.PUBLIC_KEY_LENGTH + Transformer.SIGNATURE_LENGTH + Transformer.ADDRESS_LENGTH);

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(byteSize);

			for (long timestamp : countByTimestamp.keySet()) {
				bytes.write(Ints.toByteArray(countByTimestamp.get(timestamp)));

				bytes.write(Longs.toByteArray(timestamp));

				for (TradePresenceData tradePresenceData : this.tradePresences) {
					if (tradePresenceData.getTimestamp() == timestamp) {
						bytes.write(tradePresenceData.getPublicKey());

						bytes.write(tradePresenceData.getSignature());

						bytes.write(Base58.decode(tradePresenceData.getAtAddress()));
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
