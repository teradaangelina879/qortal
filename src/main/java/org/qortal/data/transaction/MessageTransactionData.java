package org.qortal.data.transaction;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.qortal.asset.Asset;
import org.qortal.transaction.Transaction.TransactionType;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
public class MessageTransactionData extends TransactionData {

	// Properties
	private byte[] senderPublicKey;
	private int version;
	private String recipient;
	private Long assetId;
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long amount;
	private byte[] data;
	private boolean isText;
	private boolean isEncrypted;

	// Constructors

	// For JAXB
	protected MessageTransactionData() {
		super(TransactionType.MESSAGE);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.senderPublicKey;
	}

	public MessageTransactionData(BaseTransactionData baseTransactionData,
			int version, String recipient, Long assetId, long amount, byte[] data, boolean isText, boolean isEncrypted) {
		super(TransactionType.MESSAGE, baseTransactionData);

		this.senderPublicKey = baseTransactionData.creatorPublicKey;
		this.version = version;
		this.recipient = recipient;

		if (assetId != null)
			this.assetId = assetId;
		else
			this.assetId = Asset.QORT;

		this.amount = amount;
		this.data = data;
		this.isText = isText;
		this.isEncrypted = isEncrypted;
	}

	// Getters/Setters

	public byte[] getSenderPublicKey() {
		return this.senderPublicKey;
	}

	public int getVersion() {
		return this.version;
	}

	public String getRecipient() {
		return this.recipient;
	}

	public Long getAssetId() {
		return this.assetId;
	}

	public long getAmount() {
		return this.amount;
	}

	public byte[] getData() {
		return this.data;
	}

	public boolean getIsText() {
		return this.isText;
	}

	public boolean getIsEncrypted() {
		return this.isEncrypted;
	}

}
