package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class BitcoinSendRequest {

	@Schema(description = "Bitcoin BIP32 extended private key", example = "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TbTVGajEB55L1HYLg2aQMecZLXLre5YJcawpdFG66STVAWPJ")
	public String xprv58;

	@Schema(description = "Recipient's Bitcoin address ('legacy' P2PKH only)", example = "1BitcoinEaterAddressDontSendf59kuE")
	public String receivingAddress;

	@Schema(description = "Amount of BTC to send")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long bitcoinAmount;

	public BitcoinSendRequest() {
	}

}
