package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class BlockInfo {

	private byte[] signature;
	private int height;
	private long timestamp;
	private int transactionCount;

	protected BlockInfo() {
		/* For JAXB */
	}

	public BlockInfo(byte[] signature, int height, long timestamp, int transactionCount) {
		this.signature = signature;
		this.height = height;
		this.timestamp = timestamp;
		this.transactionCount = transactionCount;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public int getHeight() {
		return this.height;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public int getTransactionCount() {
		return this.transactionCount;
	}

}
