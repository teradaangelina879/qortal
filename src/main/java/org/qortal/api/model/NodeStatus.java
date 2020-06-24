package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.qortal.controller.Controller;
import org.qortal.network.Network;

@XmlAccessorType(XmlAccessType.FIELD)
public class NodeStatus {

	public final boolean isMintingPossible;
	public final boolean isSynchronizing;

	// Not always present
	public final Integer syncPercent;

	public final int numberOfConnections;

	public final int height;

	public NodeStatus() {
		isMintingPossible = Controller.getInstance().isMintingPossible();
		isSynchronizing = Controller.getInstance().isSynchronizing();

		if (isSynchronizing)
			syncPercent = Controller.getInstance().getSyncPercent();
		else
			syncPercent = null;

		numberOfConnections = Network.getInstance().getHandshakedPeers().size();

		height = Controller.getInstance().getChainHeight();
	}

}
