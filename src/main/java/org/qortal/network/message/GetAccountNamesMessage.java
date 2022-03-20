package org.qortal.network.message;

import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class GetAccountNamesMessage extends Message {

	private static final int ADDRESS_LENGTH = Transformer.ADDRESS_LENGTH;

	private String address;

	public GetAccountNamesMessage(String address) {
		this(-1, address);
	}

	private GetAccountNamesMessage(int id, String address) {
		super(id, MessageType.GET_ACCOUNT_NAMES);

		this.address = address;
	}

	public String getAddress() {
		return this.address;
	}


	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		byte[] addressBytes = new byte[ADDRESS_LENGTH];
		bytes.get(addressBytes);
		String address = Base58.encode(addressBytes);

		return new GetAccountNamesMessage(id, address);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			// Send raw address instead of base58 encoded
			byte[] address = Base58.decode(this.address);
			bytes.write(address);

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
