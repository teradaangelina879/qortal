package org.qora.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.qora.crypto.Crypto;

@XmlAccessorType(XmlAccessType.FIELD)
public class BlockMinterSummary {

	// Properties

	public int blockCount;

	public byte[] mintingAccountPublicKey;
	public String mintingAccount;

	public byte[] rewardSharePublicKey;
	public String recipientAccount;

	// Constructors

	protected BlockMinterSummary() {
	}

	/** Constructs BlockMinterSummary in non-reward-share context. */
	public BlockMinterSummary(byte[] blockMinterPublicKey, int blockCount) {
		this.blockCount = blockCount;

		this.mintingAccountPublicKey = blockMinterPublicKey;
		this.mintingAccount = Crypto.toAddress(this.mintingAccountPublicKey);
	}

	/** Constructs BlockMinterSummary in reward-share context. */
	public BlockMinterSummary(byte[] rewardSharePublicKey, int blockCount, byte[] mintingAccountPublicKey, String recipientAccount) {
		this(mintingAccountPublicKey, blockCount);

		this.rewardSharePublicKey = rewardSharePublicKey;
		this.recipientAccount = recipientAccount;
	}

}
