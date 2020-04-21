package org.qortal.api.model;

import java.math.BigDecimal;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainBuildRequest {

	@Schema(description = "AT creator's public key", example = "C6wuddsBV3HzRrXUtezE7P5MoRXp5m3mEDokRDGZB6ry")
	public byte[] creatorPublicKey;

	@Schema(description = "Initial QORT amount paid when trade agreed", example = "0.00100000")
	public BigDecimal initialQortAmount;

	@Schema(description = "Final QORT amount paid out on successful trade", example = "80.40200000")
	public BigDecimal finalQortAmount;

	@Schema(description = "QORT amount funding AT, including covering AT execution fees", example = "123.45670000")
	public BigDecimal fundingQortAmount;

	@Schema(description = "HASH160 of secret", example = "43vnftqkjxrhb5kJdkU1ZFQLEnWV")
	public byte[] secretHash;

	@Schema(description = "Bitcoin P2SH BTC balance for release of secret", example = "0.00864200")
	public BigDecimal bitcoinAmount;

	@Schema(description = "Trade time window (minutes) from trade agreement to automatic refund", example = "10080")
	public Integer tradeTimeout;

	public CrossChainBuildRequest() {
	}

}
