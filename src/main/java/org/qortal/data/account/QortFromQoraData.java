package org.qortal.data.account;

import java.math.BigDecimal;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class QortFromQoraData {

	// Properties
	private String address;
	// Not always present:
	private BigDecimal finalQortFromQora;
	private Integer finalBlockHeight;

	// Constructors

	// necessary for JAXB
	protected QortFromQoraData() {
	}

	public QortFromQoraData(String address, BigDecimal finalQortFromQora, Integer finalBlockHeight) {
		this.address = address;
		this.finalQortFromQora = finalQortFromQora;
		this.finalBlockHeight = finalBlockHeight;
	}

	// Getters/Setters

	public String getAddress() {
		return this.address;
	}

	public BigDecimal getFinalQortFromQora() {
		return this.finalQortFromQora;
	}

	public void setFinalQortFromQora(BigDecimal finalQortFromQora) {
		this.finalQortFromQora = finalQortFromQora;
	}

	public Integer getFinalBlockHeight() {
		return this.finalBlockHeight;
	}

	public void setFinalBlockHeight(Integer finalBlockHeight) {
		this.finalBlockHeight = finalBlockHeight;
	}

}
