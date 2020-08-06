package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotCreateRequest {

	@Schema(description = "Trade creator's public key", example = "2zR1WFsbM7akHghqSCYKBPk6LDP8aKiQSRS1FrwoLvoB")
	public byte[] creatorPublicKey;

	@Schema(description = "QORT amount paid out on successful trade", example = "80.40200000")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long qortAmount;

	@Schema(description = "QORT amount funding AT, including covering AT execution fees", example = "81")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long fundingQortAmount;

	@Schema(description = "Bitcoin amount wanted in return", example = "0.00864200")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long bitcoinAmount;

	@Schema(description = "Suggested trade timeout (minutes)", example = "10080")
	public int tradeTimeout;

	@Schema(description = "Bitcoin address for receiving", example = "1BitcoinEaterAddressDontSendf59kuE")
	public String receivingAddress;

	public TradeBotCreateRequest() {
	}

}
