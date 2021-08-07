package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AddressListRequest {

	@Schema(description = "A list of addresses")
	public List<String> addresses;

	public AddressListRequest() {
	}

}
