package org.qora.data.network;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;

import org.qora.account.PublicKeyAccount;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class OnlineAccount {

	protected long timestamp;
	protected byte[] signature;
	protected byte[] publicKey;

	// Constructors

	// necessary for JAXB serialization
	protected OnlineAccount() {
	}

	public OnlineAccount(long timestamp, byte[] signature, byte[] publicKey) {
		this.timestamp = timestamp;
		this.signature = signature;
		this.publicKey = publicKey;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public byte[] getPublicKey() {
		return this.publicKey;
	}

	// For JAXB
	@XmlElement(name = "address")
	protected String getAddress() {
		return new PublicKeyAccount(null, this.publicKey).getAddress();
	}

}
