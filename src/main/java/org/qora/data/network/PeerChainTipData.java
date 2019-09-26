package org.qora.data.network;

public class PeerChainTipData {

	/** Latest block height as reported by peer. */
	private Integer lastHeight;
	/** Latest block signature as reported by peer. */
	private byte[] lastBlockSignature;
	/** Latest block timestamp as reported by peer. */
	private Long lastBlockTimestamp;
	/** Latest block generator public key as reported by peer. */
	private byte[] lastBlockGenerator;

	public PeerChainTipData(Integer lastHeight, byte[] lastBlockSignature, Long lastBlockTimestamp, byte[] lastBlockGenerator) {
		this.lastHeight = lastHeight;
		this.lastBlockSignature = lastBlockSignature;
		this.lastBlockTimestamp = lastBlockTimestamp;
		this.lastBlockGenerator = lastBlockGenerator;
	}

	public Integer getLastHeight() {
		return this.lastHeight;
	}

	public byte[] getLastBlockSignature() {
		return this.lastBlockSignature;
	}

	public Long getLastBlockTimestamp() {
		return this.lastBlockTimestamp;
	}

	public byte[] getLastBlockGenerator() {
		return this.lastBlockGenerator;
	}

}
