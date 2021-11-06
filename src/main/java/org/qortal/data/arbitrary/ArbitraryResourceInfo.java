package org.qortal.data.arbitrary;

import org.qortal.data.transaction.ArbitraryTransactionData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryResourceInfo {

	public String name;
	public ArbitraryTransactionData.Service service;

	public ArbitraryResourceInfo() {
	}

}
