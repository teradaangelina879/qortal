package org.qora.network.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.qora.data.network.OnlineAccount;
import org.qora.transform.Transformer;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

public class GetOnlineAccountsMessage extends Message {
	private static final int MAX_ACCOUNT_COUNT = 1000;

	private List<OnlineAccount> onlineAccounts;

	public GetOnlineAccountsMessage(List<OnlineAccount> onlineAccounts) {
		this(-1, onlineAccounts);
	}

	private GetOnlineAccountsMessage(int id, List<OnlineAccount> onlineAccounts) {
		super(id, MessageType.GET_ONLINE_ACCOUNTS);

		this.onlineAccounts = onlineAccounts;
	}

	public List<OnlineAccount> getOnlineAccounts() {
		return this.onlineAccounts;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		final int accountCount = bytes.getInt();

		if (accountCount > MAX_ACCOUNT_COUNT)
			return null;

		List<OnlineAccount> onlineAccounts = new ArrayList<>(accountCount);

		for (int i = 0; i < accountCount; ++i) {
			long timestamp = bytes.getLong();

			byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
			bytes.get(publicKey);

			onlineAccounts.add(new OnlineAccount(timestamp, null, publicKey));
		}

		return new GetOnlineAccountsMessage(id, onlineAccounts);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(this.onlineAccounts.size()));

			for (int i = 0; i < this.onlineAccounts.size(); ++i) {
				OnlineAccount onlineAccount = this.onlineAccounts.get(i);
				bytes.write(Longs.toByteArray(onlineAccount.getTimestamp()));

				bytes.write(onlineAccount.getPublicKey());
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
