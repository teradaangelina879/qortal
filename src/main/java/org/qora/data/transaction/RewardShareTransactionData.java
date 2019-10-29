package org.qora.data.transaction;

import java.math.BigDecimal;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qora.transaction.Transaction.TransactionType;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = {TransactionData.class})
// JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("REWARD_SHARE")
public class RewardShareTransactionData extends TransactionData {

	@Schema(example = "minter_public_key")
	private byte[] minterPublicKey;

	private String recipient;

	@Schema(example = "reward_share_public_key")
	private byte[] rewardSharePublicKey;

	private BigDecimal sharePercent;

	// No need to ever expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private BigDecimal previousSharePercent;

	// Constructors

	// For JAXB
	protected RewardShareTransactionData() {
		super(TransactionType.REWARD_SHARE);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.minterPublicKey;
	}

	/** From repository */
	public RewardShareTransactionData(BaseTransactionData baseTransactionData,
			String recipient, byte[] rewardSharePublicKey, BigDecimal sharePercent, BigDecimal previousSharePercent) {
		super(TransactionType.REWARD_SHARE, baseTransactionData);

		this.minterPublicKey = baseTransactionData.creatorPublicKey;
		this.recipient = recipient;
		this.rewardSharePublicKey = rewardSharePublicKey;
		this.sharePercent = sharePercent;
		this.previousSharePercent = previousSharePercent;
	}

	/** From network/API */
	public RewardShareTransactionData(BaseTransactionData baseTransactionData,
			String recipient, byte[] rewardSharePublicKey, BigDecimal sharePercent) {
		this(baseTransactionData, recipient, rewardSharePublicKey, sharePercent, null);
	}

	// Getters / setters

	public byte[] getMinterPublicKey() {
		return this.minterPublicKey;
	}

	public String getRecipient() {
		return this.recipient;
	}

	public byte[] getRewardSharePublicKey() {
		return this.rewardSharePublicKey;
	}

	public BigDecimal getSharePercent() {
		return this.sharePercent;
	}

	public BigDecimal getPreviousSharePercent() {
		return this.previousSharePercent;
	}

	public void setPreviousSharePercent(BigDecimal previousSharePercent) {
		this.previousSharePercent = previousSharePercent;
	}

}
