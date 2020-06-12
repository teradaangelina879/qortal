package org.qortal.api.websocket;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;

interface ApiWebSocket {

	default String getPathInfo(Session session) {
		ServletUpgradeRequest upgradeRequest = (ServletUpgradeRequest) session.getUpgradeRequest();
		return upgradeRequest.getHttpServletRequest().getPathInfo();
	}

	default Map<String, String> getPathParams(Session session, String pathSpec) {
		UriTemplatePathSpec uriTemplatePathSpec = new UriTemplatePathSpec(pathSpec);
		return uriTemplatePathSpec.getPathParams(this.getPathInfo(session));
	}

	default void marshall(Writer writer, Object object) throws IOException {
		Marshaller marshaller = createMarshaller(object.getClass());

		try {
			marshaller.marshal(object, writer);
		} catch (JAXBException e) {
			throw new IOException("Unable to create marshall object for websocket", e);
		}
	}

	default void marshall(Writer writer, Collection<?> collection) throws IOException {
		// If collection is empty then we're returning "[]" anyway
		if (collection.isEmpty()) {
			writer.append("[]");
			return;
		}

		// Grab an entry from collection so we can determine type
		Object entry = collection.iterator().next();

		Marshaller marshaller = createMarshaller(entry.getClass());

		try {
			marshaller.marshal(collection, writer);
		} catch (JAXBException e) {
			throw new IOException("Unable to create marshall object for websocket", e);
		}
	}

	private static Marshaller createMarshaller(Class<?> objectClass) {
		try {
			// Create JAXB context aware of object's class
			JAXBContext jc = JAXBContextFactory.createContext(new Class[] { objectClass }, null);

			// Create marshaller
			Marshaller marshaller = jc.createMarshaller();

			// Set the marshaller media type to JSON
			marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");

			// Tell marshaller not to include JSON root element in the output
			marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);

			return marshaller;
		} catch (JAXBException e) {
			throw new RuntimeException("Unable to create websocket marshaller", e);
		}
	}

}
