package org.qortal.data.network;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.Arrays;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class OnlineTradeData {

	protected long timestamp;
	protected byte[] publicKey; // Could be BOB's or ALICE's
	protected byte[] signature; // Not always present
	protected String atAddress; // Not always present

	// Constructors

	// necessary for JAXB serialization
	protected OnlineTradeData() {
	}

	public OnlineTradeData(long timestamp, byte[] publicKey, byte[] signature, String atAddress) {
		this.timestamp = timestamp;
		this.publicKey = publicKey;
		this.signature = signature;
		this.atAddress = atAddress;
	}

	public OnlineTradeData(long timestamp, byte[] publicKey) {
		this(timestamp, publicKey, null, null);
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public byte[] getPublicKey() {
		return this.publicKey;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public String getAtAddress() {
		return this.atAddress;
	}

	// Comparison

	@Override
	public boolean equals(Object other) {
		if (other == this)
			return true;

		if (!(other instanceof OnlineTradeData))
			return false;

		OnlineTradeData otherOnlineTradeData = (OnlineTradeData) other;

		// Very quick comparison
		if (otherOnlineTradeData.timestamp != this.timestamp)
			return false;

		if (!Arrays.equals(otherOnlineTradeData.publicKey, this.publicKey))
			return false;

		if (otherOnlineTradeData.atAddress != null && !otherOnlineTradeData.atAddress.equals(this.atAddress))
			return false;

		if (this.atAddress != null && !this.atAddress.equals(otherOnlineTradeData.atAddress))
			return false;

		if (!Arrays.equals(otherOnlineTradeData.signature, this.signature))
			return false;

		return true;
	}

	@Override
	public int hashCode() {
		// Pretty lazy implementation
		return (int) this.timestamp;
	}

}
