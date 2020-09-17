package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.qortal.crosschain.AcctMode;
import org.qortal.data.crosschain.CrossChainTradeData;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainOfferSummary {

	// Properties

	@Schema(description = "AT's Qortal address")
	public String qortalAtAddress;

	@Schema(description = "AT creator's Qortal address")
	public String qortalCreator;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long qortAmount;

	@Schema(description = "Bitcoin amount - DEPRECATED: use foreignAmount")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	@Deprecated
	private long btcAmount;

	@Schema(description = "Foreign blockchain amount")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long foreignAmount;

	@Schema(description = "Suggested trade timeout (minutes)", example = "10080")
	private int tradeTimeout;

	@Schema(description = "Current AT execution mode")
	private AcctMode mode;

	private long timestamp;

	private String partnerQortalReceivingAddress;

	protected CrossChainOfferSummary() {
		/* For JAXB */
	}

	public CrossChainOfferSummary(CrossChainTradeData crossChainTradeData, long timestamp) {
		this.qortalAtAddress = crossChainTradeData.qortalAtAddress;
		this.qortalCreator = crossChainTradeData.qortalCreator;
		this.qortAmount = crossChainTradeData.qortAmount;
		this.btcAmount = crossChainTradeData.expectedBitcoin;
		this.foreignAmount = crossChainTradeData.expectedBitcoin;
		this.tradeTimeout = crossChainTradeData.tradeTimeout;
		this.mode = crossChainTradeData.mode;
		this.timestamp = timestamp;
		this.partnerQortalReceivingAddress = crossChainTradeData.qortalPartnerReceivingAddress;
	}

	public String getQortalAtAddress() {
		return this.qortalAtAddress;
	}

	public String getQortalCreator() {
		return this.qortalCreator;
	}

	public long getQortAmount() {
		return this.qortAmount;
	}

	public long getBtcAmount() {
		return this.btcAmount;
	}

	public long getForeignAmount() {
		return this.foreignAmount;
	}

	public int getTradeTimeout() {
		return this.tradeTimeout;
	}

	public AcctMode getMode() {
		return this.mode;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public String getPartnerQortalReceivingAddress() {
		return this.partnerQortalReceivingAddress;
	}

	// For debugging mostly

	public String toString() {
		return String.format("%s: %s", this.qortalAtAddress, this.mode);
	}

}
