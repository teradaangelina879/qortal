package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class NodeStatus {

	public boolean isMintingPossible;
	public boolean isSynchronizing;

	// Not always present
	public Integer syncPercent;

	public NodeStatus() {
	}

}
