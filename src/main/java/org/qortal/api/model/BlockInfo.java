package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.qortal.data.account.RewardShareData;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

@XmlAccessorType(XmlAccessType.FIELD)
public class BlockInfo {

	private byte[] signature;
	private int height;
	private long timestamp;
	private int transactionCount;
	private String minterAddress;

	protected BlockInfo() {
		/* For JAXB */
	}

	public BlockInfo(byte[] signature, int height, long timestamp, int transactionCount, String minterAddress) {
		this.signature = signature;
		this.height = height;
		this.timestamp = timestamp;
		this.transactionCount = transactionCount;
		this.minterAddress = minterAddress;
	}

	public BlockInfo(BlockData blockData) {
		// Convert BlockData to BlockInfo, using additional data
		this.minterAddress = "unknown?";

		try (final Repository repository = RepositoryManager.getRepository()) {
			RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(blockData.getMinterPublicKey());
			if (rewardShareData != null)
				this.minterAddress = rewardShareData.getMintingAccount();
		} catch (DataException e) {
			// We'll carry on with placeholder minterAddress then...
		}

		this.signature = blockData.getSignature();
		this.height = blockData.getHeight();
		this.timestamp = blockData.getTimestamp();
		this.transactionCount = blockData.getTransactionCount();
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

	public String getMinterAddress() {
		return this.minterAddress;
	}

}
