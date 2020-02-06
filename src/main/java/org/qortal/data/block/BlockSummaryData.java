package org.qortal.data.block;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.qortal.transform.block.BlockTransformer;

@XmlAccessorType(XmlAccessType.FIELD)
public class BlockSummaryData {

	// Properties
	private int height;
	private byte[] signature;
	private byte[] minterPublicKey;
	private int onlineAccountsCount;

	// Optional, set after construction
	private Integer minterLevel;

	// Constructors

	protected BlockSummaryData() {
	}

	public BlockSummaryData(int height, byte[] signature, byte[] minterPublicKey, int onlineAccountsCount) {
		this.height = height;
		this.signature = signature;
		this.minterPublicKey = minterPublicKey;
		this.onlineAccountsCount = onlineAccountsCount;
	}

	public BlockSummaryData(BlockData blockData) {
		this.height = blockData.getHeight();
		this.signature = blockData.getSignature();
		this.minterPublicKey = blockData.getMinterPublicKey();

		byte[] encodedOnlineAccounts = blockData.getEncodedOnlineAccounts();
		if (encodedOnlineAccounts != null) {
			this.onlineAccountsCount = BlockTransformer.decodeOnlineAccounts(encodedOnlineAccounts).size();
		} else {
			this.onlineAccountsCount = 0;
		}
	}

	// Getters / setters

	public int getHeight() {
		return this.height;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public byte[] getMinterPublicKey() {
		return this.minterPublicKey;
	}

	public int getOnlineAccountsCount() {
		return this.onlineAccountsCount;
	}

	public Integer getMinterLevel() {
		return this.minterLevel;
	}

	public void setMinterLevel(Integer minterLevel) {
		this.minterLevel = minterLevel;
	}

}
