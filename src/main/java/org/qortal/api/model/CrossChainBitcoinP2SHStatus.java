package org.qortal.api.model;

import java.math.BigDecimal;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainBitcoinP2SHStatus {

	@Schema(description = "Bitcoin P2SH address", example = "3CdH27kTpV8dcFHVRYjQ8EEV5FJg9X8pSJ (mainnet), 2fMiRRXVsxhZeyfum9ifybZvaMHbQTmwdZw (testnet)")
	public String bitcoinP2shAddress;

	@Schema(description = "Bitcoin P2SH balance")
	public BigDecimal bitcoinP2shBalance;

	@Schema(description = "Can P2SH redeem yet?")
	public boolean canRedeem;

	@Schema(description = "Can P2SH refund yet?")
	public boolean canRefund;

	@Schema(description = "Secret extracted by P2SH redeemer")
	public byte[] secret;

	public CrossChainBitcoinP2SHStatus() {
	}

}
