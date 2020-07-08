package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotRespondRequest {

	@Schema(description = "Qortal AT address", example = "AH3e3jHEsGHPVQPDiJx4pYqgVi72auxgVy")
	public String atAddress;

	@Schema(description = "Bitcoin BIP32 extended private key", example = "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TbTVGajEB55L1HYLg2aQMecZLXLre5YJcawpdFG66STVAWPJ")
	public String xprv58;

	public TradeBotRespondRequest() {
	}

}
