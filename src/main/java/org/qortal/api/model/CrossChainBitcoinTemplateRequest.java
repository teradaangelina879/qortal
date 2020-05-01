package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainBitcoinTemplateRequest {

	@Schema(description = "Bitcoin P2PKH address for refund", example = "1BwG6aG2GapFX5b4JT4ohbsYvj1xZ8d2EJ (mainnet), mrTDPdM15cFWJC4g223BXX5snicfVJBx6M (testnet)")
	public String refundAddress;

	@Schema(description = "Bitcoin P2PKH address for redeem", example = "1BwG6aG2GapFX5b4JT4ohbsYvj1xZ8d2EJ (mainnet), mrTDPdM15cFWJC4g223BXX5snicfVJBx6M (testnet)")
	public String redeemAddress;

	@Schema(description = "Qortal AT address")
	public String atAddress;

	public CrossChainBitcoinTemplateRequest() {
	}

}
