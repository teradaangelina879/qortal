package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainSecretRequest {

	@Schema(description = "AT's 'recipient' public key", example = "C6wuddsBV3HzRrXUtezE7P5MoRXp5m3mEDokRDGZB6ry")
	public byte[] recipientPublicKey;

	public String atAddress;

	@Schema(description = "32-byte secret", example = "6gVbAXCVzJXAWwtAVGAfgAkkXpeXvPUwSciPmCfSfXJG")
	public byte[] secret;

	public CrossChainSecretRequest() {
	}

}
