package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotRespondRequest {

	@Schema(description = "Qortal AT address", example = "Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	public String atAddress;

	@Schema(description = "Bitcoin BIP32 extended private key", example = "xprv___________________________________________________________________________________________________________")
	public String xprv58;

	@Schema(description = "Qortal address for receiving QORT from AT", example = "Qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
	public String receivingAddress;

	public TradeBotRespondRequest() {
	}

}
