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

public class OnlineAccountsMessage extends Message {
	private static final int MAX_ACCOUNT_COUNT = 1000;

	private List<OnlineAccount> onlineAccounts;

	public OnlineAccountsMessage(List<OnlineAccount> onlineAccounts) {
		this(-1, onlineAccounts);
	}

	private OnlineAccountsMessage(int id, List<OnlineAccount> onlineAccounts) {
		super(id, MessageType.ONLINE_ACCOUNTS);

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

			byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
			bytes.get(signature);

			byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
			bytes.get(publicKey);

			OnlineAccount onlineAccount = new OnlineAccount(timestamp, signature, publicKey);
			onlineAccounts.add(onlineAccount);
		}

		return new OnlineAccountsMessage(id, onlineAccounts);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(this.onlineAccounts.size()));

			for (int i = 0; i < this.onlineAccounts.size(); ++i) {
				OnlineAccount onlineAccount = this.onlineAccounts.get(i);

				bytes.write(Longs.toByteArray(onlineAccount.getTimestamp()));

				bytes.write(onlineAccount.getSignature());

				bytes.write(onlineAccount.getPublicKey());
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
