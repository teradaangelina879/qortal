package org.qortal.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.crypto.Crypto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.AccessMode;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class MintingAccountData {

	// Properties
	@Schema(hidden = true)
	@XmlTransient
	protected byte[] privateKey;

	// Not always present - used by API if not null
	@XmlTransient
	@Schema(hidden = true)
	protected byte[] publicKey;
	protected String mintingAccount;
	protected String recipientAccount;
	protected String address;

	// Constructors

	// For JAXB
	protected MintingAccountData() {
	}

	public MintingAccountData(byte[] privateKey) {
		this.privateKey = privateKey;
		this.publicKey = PrivateKeyAccount.toPublicKey(privateKey);
	}

	public MintingAccountData(byte[] privateKey, RewardShareData rewardShareData) {
		this(privateKey);

		if (rewardShareData != null) {
			this.recipientAccount = rewardShareData.getRecipient();
			this.mintingAccount = Crypto.toAddress(rewardShareData.getMinterPublicKey());
		} else {
			this.address = Crypto.toAddress(this.publicKey);
		}
	}

	// Getters/Setters

	public byte[] getPrivateKey() {
		return this.privateKey;
	}

	@XmlElement(name = "publicKey")
	@Schema(accessMode = AccessMode.READ_ONLY)
	public byte[] getPublicKey() {
		return this.publicKey;
	}

}
