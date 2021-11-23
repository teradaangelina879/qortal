package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryResourceSummary {

	public enum ArbitraryResourceStatus {
		DOWNLOADING,
		DOWNLOADED,
		BUILDING,
		READY,
		MISSING_DATA,
		BUILD_FAILED,
		UNSUPPORTED,
		BLACKLISTED
	}

	public ArbitraryResourceStatus status;

	public ArbitraryResourceSummary() {
	}

	public ArbitraryResourceSummary(ArbitraryResourceStatus status) {
		this.status = status;
	}

}
