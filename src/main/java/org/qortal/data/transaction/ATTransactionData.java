package org.qortal.data.transaction;

import java.math.BigDecimal;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

import org.qortal.account.NullAccount;
import org.qortal.transaction.Transaction.TransactionType;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
public class ATTransactionData extends TransactionData {

	// Properties

	private String atAddress;

	private String recipient;

	@XmlTransient
	@Schema(hidden = true)
	// Not always present
	private Long amount;

	// Not always present
	private Long assetId;

	private byte[] message;

	// Constructors

	// For JAXB
	protected ATTransactionData() {
		super(TransactionType.AT);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = NullAccount.PUBLIC_KEY;
	}

	/** From repository */
	public ATTransactionData(BaseTransactionData baseTransactionData, String atAddress, String recipient, Long amount, Long assetId, byte[] message) {
		super(TransactionType.AT, baseTransactionData);

		this.creatorPublicKey = NullAccount.PUBLIC_KEY;
		this.atAddress = atAddress;
		this.recipient = recipient;
		this.amount = amount;
		this.assetId = assetId;
		this.message = message;
	}

	// Getters/Setters

	public String getATAddress() {
		return this.atAddress;
	}

	public String getRecipient() {
		return this.recipient;
	}

	public Long getAmount() {
		return this.amount;
	}

	public Long getAssetId() {
		return this.assetId;
	}

	public byte[] getMessage() {
		return this.message;
	}

	// Some JAXB/API-related getters

	@Schema(name = "amount")
	public BigDecimal getAmountJaxb() {
		if (this.amount == null)
			return null;

		return BigDecimal.valueOf(this.amount, 8);
	}

}
