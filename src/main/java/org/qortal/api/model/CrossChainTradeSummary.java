package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.qortal.data.crosschain.CrossChainTradeData;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainTradeSummary {

	private long tradeTimestamp;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long qortAmount;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long btcAmount;

	protected CrossChainTradeSummary() {
		/* For JAXB */
	}

	public CrossChainTradeSummary(CrossChainTradeData crossChainTradeData, long timestamp) {
		this.tradeTimestamp = timestamp;
		this.qortAmount = crossChainTradeData.qortAmount;
		this.btcAmount = crossChainTradeData.expectedBitcoin;
	}

	public long getTradeTimestamp() {
		return this.tradeTimestamp;
	}

	public long getQortAmount() {
		return this.qortAmount;
	}

	public long getBtcAmount() {
		return this.btcAmount;
	}

}
