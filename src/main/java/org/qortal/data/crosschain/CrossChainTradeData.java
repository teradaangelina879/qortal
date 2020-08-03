package org.qortal.data.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.qortal.crosschain.BTC;
import org.qortal.crosschain.BTCACCT;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainTradeData {

	// Properties

	@Schema(description = "AT's Qortal address")
	public String qortalAtAddress;

	@Schema(description = "AT creator's Qortal address")
	public String qortalCreator;

	@Schema(description = "AT creator's Qortal trade address")
	public String qortalCreatorTradeAddress;

	@Schema(description = "AT creator's Bitcoin trade public-key-hash (PKH)")
	public byte[] creatorBitcoinPKH;

	@Schema(description = "Timestamp when AT was created (milliseconds since epoch)")
	public long creationTimestamp;

	@Schema(description = "Suggested trade timeout (minutes)", example = "10080")
	public int tradeTimeout;

	@Schema(description = "AT's current QORT balance")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long qortBalance;

	@Schema(description = "HASH160 of 32-byte secret-A")
	public byte[] hashOfSecretA;

	@Schema(description = "HASH160 of 32-byte secret-B")
	public byte[] hashOfSecretB;

	@Schema(description = "Final QORT payment that will be sent to Qortal trade partner")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long qortAmount;

	@Schema(description = "Trade partner's Qortal address (trade begins when this is set)")
	public String qortalPartnerAddress;

	@Schema(description = "Timestamp when AT switched to trade mode")
	public Long tradeModeTimestamp;

	@Schema(description = "How long from AT creation until AT triggers automatic refund to AT creator (minutes)")
	public int refundTimeout;

	@Schema(description = "Actual Qortal block height when AT will automatically refund to AT creator (after trade begins)")
	public Integer tradeRefundHeight;

	@Schema(description = "Amount, in BTC, that AT creator expects Bitcoin P2SH to pay out (excluding miner fees)")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long expectedBitcoin;

	public BTCACCT.Mode mode;

	@Schema(description = "Suggested Bitcoin P2SH-A nLockTime based on trade timeout")
	public Integer lockTimeA;

	@Schema(description = "Suggested Bitcoin P2SH-B nLockTime based on trade timeout")
	public Integer lockTimeB;

	@Schema(description = "Trade partner's Bitcoin public-key-hash (PKH)")
	public byte[] partnerBitcoinPKH;

	// Constructors

	// Necessary for JAXB
	public CrossChainTradeData() {
	}

	// We can represent BitcoinPKH as an address
	@XmlElement(name = "creatorBitcoinAddress")
	@Schema(description = "AT creator's trading Bitcoin PKH in address form")
	public String getCreatorBitcoinAddress() {
		if (this.creatorBitcoinPKH == null)
			return null;

		return BTC.getInstance().pkhToAddress(this.creatorBitcoinPKH);
	}

	// We can represent BitcoinPKH as an address
	@XmlElement(name = "recipientBitcoinAddress")
	@Schema(description = "Trade partner's trading Bitcoin PKH in address form")
	public String getRecipientBitcoinAddress() {
		if (this.partnerBitcoinPKH == null)
			return null;

		return BTC.getInstance().pkhToAddress(this.partnerBitcoinPKH);
	}

}
