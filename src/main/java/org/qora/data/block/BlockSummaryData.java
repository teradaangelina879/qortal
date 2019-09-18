package org.qora.data.block;

import org.qora.transform.block.BlockTransformer;

public class BlockSummaryData {

	// Properties
	private int height;
	private byte[] signature;
	private byte[] generatorPublicKey;
	private int onlineAccountsCount;

	// Constructors
	public BlockSummaryData(int height, byte[] signature, byte[] generatorPublicKey, int onlineAccountsCount) {
		this.height = height;
		this.signature = signature;
		this.generatorPublicKey = generatorPublicKey;
		this.onlineAccountsCount = onlineAccountsCount;
	}

	public BlockSummaryData(BlockData blockData) {
		this.height = blockData.getHeight();
		this.signature = blockData.getSignature();
		this.generatorPublicKey = blockData.getGeneratorPublicKey();

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

	public byte[] getGeneratorPublicKey() {
		return this.generatorPublicKey;
	}

	public int getOnlineAccountsCount() {
		return this.onlineAccountsCount;
	}

}
