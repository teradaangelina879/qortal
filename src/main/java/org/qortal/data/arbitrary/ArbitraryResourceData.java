package org.qortal.data.arbitrary;

import org.qortal.arbitrary.misc.Service;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.Objects;

@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryResourceData {

	public String name;
	public Service service;
	public String identifier;
	public ArbitraryResourceStatus status;
	public ArbitraryResourceMetadata metadata;

	public Integer size;
	public Long created;
	public Long updated;

	public ArbitraryResourceData() {
	}

	@Override
	public String toString() {
		return String.format("%s %s %s", name, service, identifier);
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (!(o instanceof ArbitraryResourceData))
			return false;

		ArbitraryResourceData other = (ArbitraryResourceData) o;

		return Objects.equals(this.name, other.name) &&
				Objects.equals(this.service, other.service) &&
				Objects.equals(this.identifier, other.identifier);
	}

}
