package org.qora.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class ApiOnlineAccount {

	protected long timestamp;
	protected byte[] signature;
	protected byte[] publicKey;
	protected String generatorAddress;
	protected String recipientAddress;

	// Constructors

	// necessary for JAXB serialization
	protected ApiOnlineAccount() {
	}

	public ApiOnlineAccount(long timestamp, byte[] signature, byte[] publicKey, String generatorAddress, String recipientAddress) {
		this.timestamp = timestamp;
		this.signature = signature;
		this.publicKey = publicKey;
		this.generatorAddress = generatorAddress;
		this.recipientAddress = recipientAddress;
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

	public String getGeneratorAddress() {
		return this.generatorAddress;
	}

	public String getRecipientAddress() {
		return this.recipientAddress;
	}

}
