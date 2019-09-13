package org.qora.data.transaction;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;

import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qora.account.GenesisAccount;
import org.qora.block.GenesisBlock;
import org.qora.transaction.Transaction.TransactionType;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = {TransactionData.class})
// JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("ACCOUNT_LEVEL")
public class AccountLevelTransactionData extends TransactionData {

	private String target;
	private int level;
	private Integer previousLevel;

	// Constructors

	// For JAXB
	protected AccountLevelTransactionData() {
		super(TransactionType.ACCOUNT_LEVEL);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		/*
		 *  If we're being constructed as part of the genesis block info inside blockchain config
		 *  and no specific creator's public key is supplied
		 *  then use genesis account's public key.
		 */
		if (parent instanceof GenesisBlock.GenesisInfo && this.creatorPublicKey == null)
			this.creatorPublicKey = GenesisAccount.PUBLIC_KEY;
	}

	/** From repository */
	public AccountLevelTransactionData(BaseTransactionData baseTransactionData,
			String target, int level, Integer previousLevel) {
		super(TransactionType.ACCOUNT_LEVEL, baseTransactionData);

		this.target = target;
		this.level = level;
		this.previousLevel = previousLevel;
	}

	/** From network/API */
	public AccountLevelTransactionData(BaseTransactionData baseTransactionData,
			String target, int level) {
		this(baseTransactionData, target, level, null);
	}

	// Getters / setters

	public String getTarget() {
		return this.target;
	}

	public int getLevel() {
		return this.level;
	}

	public Integer getPreviousLevel() {
		return this.previousLevel;
	}

	public void setPreviousLevel(Integer previousLevel) {
		this.previousLevel = previousLevel;
	}

	// Re-expose to JAXB

	@XmlElement(name = "creatorPublicKey")
	@Schema(name = "creatorPublicKey", description = "creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	public byte[] getAccountLevelCreatorPublicKey() {
		return super.getCreatorPublicKey();
	}

	@XmlElement(name = "creatorPublicKey")
	@Schema(name = "creatorPublicKey", description = "creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	public void setAccountLevelCreatorPublicKey(byte[] creatorPublicKey) {
		super.setCreatorPublicKey(creatorPublicKey);
	}

}
