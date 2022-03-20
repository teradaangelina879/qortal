package org.qortal.network.message;

import org.qortal.naming.Name;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class GetNameMessage extends Message {

	private String name;

	public GetNameMessage(String address) {
		this(-1, address);
	}

	private GetNameMessage(int id, String name) {
		super(id, MessageType.GET_NAME);

		this.name = name;
	}

	public String getName() {
		return this.name;
	}


	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		try {
			String name = Serialization.deserializeSizedStringV2(bytes, Name.MAX_NAME_SIZE);

			return new GetNameMessage(id, name);
		} catch (TransformationException e) {
			return null;
		}
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			Serialization.serializeSizedStringV2(bytes, this.name);

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
