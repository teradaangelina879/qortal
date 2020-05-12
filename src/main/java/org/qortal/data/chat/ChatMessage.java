package org.qortal.data.chat;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ChatMessage {

	// Properties

	private long timestamp;

	private int txGroupId;

	private byte[] senderPublicKey;

	/* Address of sender */
	private String sender;

	/* Address of recipient (if any) */
	private String recipient; // can be null

	private byte[] data;

	private boolean isText;
	private boolean isEncrypted;

	// Constructors

	protected ChatMessage() {
		/* For JAXB */
	}

	// For repository use
	public ChatMessage(long timestamp, int txGroupId, byte[] senderPublicKey, String sender,
			String recipient, byte[] data, boolean isText, boolean isEncrypted) {
		this.timestamp = timestamp;
		this.txGroupId = txGroupId;
		this.senderPublicKey = senderPublicKey;
		this.sender = sender;
		this.recipient = recipient;
		this.data = data;
		this.isText = isText;
		this.isEncrypted = isEncrypted;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public int getTxGroupId() {
		return this.txGroupId;
	}

	public byte[] getSenderPublicKey() {
		return this.senderPublicKey;
	}

	public String getSender() {
		return this.sender;
	}

	public String getRecipient() {
		return this.recipient;
	}

	public byte[] getData() {
		return this.data;
	}

	public boolean isText() {
		return this.isText;
	}

	public boolean isEncrypted() {
		return this.isEncrypted;
	}

}
