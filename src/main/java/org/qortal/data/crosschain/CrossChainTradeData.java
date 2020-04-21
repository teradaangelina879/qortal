package org.qortal.data.crosschain;

import java.math.BigDecimal;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainTradeData {

	public static enum Mode { OFFER, TRADE };

	// Properties

	@Schema(description = "AT's Qortal address")
	public String qortalAddress;

	@Schema(description = "AT creator's Qortal address")
	public String qortalCreator;

	@Schema(description = "Timestamp when AT was created (milliseconds since epoch)")
	public long creationTimestamp;

	@Schema(description = "AT's current QORT balance")
	public BigDecimal qortBalance;

	@Schema(description = "HASH160 of 32-byte secret")
	public byte[] secretHash;

	@Schema(description = "Initial QORT payment that will be sent to Qortal trade partner")
	public BigDecimal initialPayout;

	@Schema(description = "Final QORT payment that will be sent to Qortal trade partner")
	public BigDecimal redeemPayout;

	@Schema(description = "Trade partner's Qortal address (trade begins when this is set)")
	public String qortalRecipient;

	@Schema(description = "How long from beginning trade until AT triggers automatic refund to AT creator (minutes)")
	public long tradeRefundTimeout;

	@Schema(description = "Actual Qortal block height when AT will automatically refund to AT creator (after trade begins)")
	public Integer tradeRefundHeight;

	@Schema(description = "Amount, in BTC, that AT creator expects Bitcoin P2SH to pay out (excluding miner fees)")
	public BigDecimal expectedBitcoin;

	public Mode mode;

	// Constructors

	// Necessary for JAXB
	public CrossChainTradeData() {
	}

}
