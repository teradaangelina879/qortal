package org.qortal.api.model;

import java.math.BigDecimal;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainBitcoinRefundRequest {

	@Schema(description = "Bitcoin PRIVATE KEY for refund", example = "cSP3zTb6bfm8GATtAcEJ8LqYtNQmzZ9jE2wQUVnZGiBzojDdrwKV")
	public byte[] refundPrivateKey;

	@Schema(description = "Bitcoin P2PKH address for redeem", example = "1BwG6aG2GapFX5b4JT4ohbsYvj1xZ8d2EJ (mainnet), mrTDPdM15cFWJC4g223BXX5snicfVJBx6M (testnet)")
	public String redeemAddress;

	@Schema(description = "Qortal AT address")
	public String atAddress;

	@Schema(description = "Bitcoin miner fee", example = "0.00001000")
	public BigDecimal bitcoinMinerFee;

	public CrossChainBitcoinRefundRequest() {
	}

}
