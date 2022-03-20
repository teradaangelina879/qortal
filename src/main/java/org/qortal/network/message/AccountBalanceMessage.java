package org.qortal.network.message;

import com.google.common.primitives.Longs;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class AccountBalanceMessage extends Message {

	private static final int ADDRESS_LENGTH = Transformer.ADDRESS_LENGTH;

	private final AccountBalanceData accountBalanceData;

	public AccountBalanceMessage(AccountBalanceData accountBalanceData) {
		super(MessageType.ACCOUNT_BALANCE);

		this.accountBalanceData = accountBalanceData;
	}

	public AccountBalanceMessage(int id, AccountBalanceData accountBalanceData) {
		super(id, MessageType.ACCOUNT_BALANCE);

		this.accountBalanceData = accountBalanceData;
	}

	public AccountBalanceData getAccountBalanceData() {
		return this.accountBalanceData;
	}


	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws UnsupportedEncodingException {
		byte[] addressBytes = new byte[ADDRESS_LENGTH];
		byteBuffer.get(addressBytes);
		String address = Base58.encode(addressBytes);

		long assetId = byteBuffer.getLong();

		long balance = byteBuffer.getLong();

		AccountBalanceData accountBalanceData = new AccountBalanceData(address, assetId, balance);
		return new AccountBalanceMessage(id, accountBalanceData);
	}

	@Override
	protected byte[] toData() {
		if (this.accountBalanceData == null) {
			return null;
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			// Send raw address instead of base58 encoded
			byte[] address = Base58.decode(this.accountBalanceData.getAddress());
			bytes.write(address);

			bytes.write(Longs.toByteArray(this.accountBalanceData.getAssetId()));

			bytes.write(Longs.toByteArray(this.accountBalanceData.getBalance()));

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

	public AccountBalanceMessage cloneWithNewId(int newId) {
		AccountBalanceMessage clone = new AccountBalanceMessage(this.accountBalanceData);
		clone.setId(newId);
		return clone;
	}

}
