package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainSecretRequest {

	@Schema(description = "Public key to match AT's 'recipient'", example = "C6wuddsBV3HzRrXUtezE7P5MoRXp5m3mEDokRDGZB6ry")
	public byte[] recipientPublicKey;

	@Schema(description = "Qortal AT address")
	public String atAddress;

	@Schema(description = "secret-A + secret-B (64 bytes)", example = "2gt2nSVBFknLfdU5buKtScLuTibkt9C3x6PZVqnA3AJ6BdEf3A9RbSj5Hn5QkvavdTTfmttNEaYEVw34TZdz135Q")
	public byte[] secret;

	public CrossChainSecretRequest() {
	}

}
