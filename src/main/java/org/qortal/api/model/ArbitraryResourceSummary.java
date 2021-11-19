package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryResourceSummary {

	public enum ArbitraryResourceStatus {
		NOT_STARTED,
		DOWNLOADING,
		DOWNLOADED,
		BUILDING,
		READY,
		DOWNLOAD_FAILED,
		BUILD_FAILED,
		UNSUPPORTED
	}

	public ArbitraryResourceStatus status;

	public ArbitraryResourceSummary() {
	}

	public ArbitraryResourceSummary(ArbitraryResourceStatus status) {
		this.status = status;
	}

}
