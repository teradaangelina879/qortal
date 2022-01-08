package org.qortal.data.arbitrary;

import org.qortal.arbitrary.misc.Service;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryResourceInfo {

	public String name;
	public Service service;
	public String identifier;
	public ArbitraryResourceStatus status;

	public ArbitraryResourceInfo() {
	}

}
