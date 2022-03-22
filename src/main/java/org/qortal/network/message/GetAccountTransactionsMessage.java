package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class GetAccountTransactionsMessage extends Message {

	private static final int ADDRESS_LENGTH = Transformer.ADDRESS_LENGTH;

	private String address;
	private int limit;
	private int offset;

	public GetAccountTransactionsMessage(String address, int limit, int offset) {
		this(-1, address, limit, offset);
	}

	private GetAccountTransactionsMessage(int id, String address, int limit, int offset) {
		super(id, MessageType.GET_ACCOUNT_TRANSACTIONS);

		this.address = address;
		this.limit = limit;
		this.offset = offset;
	}

	public String getAddress() {
		return this.address;
	}

	public int getLimit() { return this.limit; }

	public int getOffset() { return this.offset; }

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		byte[] addressBytes = new byte[ADDRESS_LENGTH];
		bytes.get(addressBytes);
		String address = Base58.encode(addressBytes);

		int limit = bytes.getInt();

		int offset = bytes.getInt();

		return new GetAccountTransactionsMessage(id, address, limit, offset);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			// Send raw address instead of base58 encoded
			byte[] address = Base58.decode(this.address);
			bytes.write(address);

			bytes.write(Ints.toByteArray(this.limit));

			bytes.write(Ints.toByteArray(this.offset));

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
