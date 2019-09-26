package org.qora.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Before;
import org.junit.Test;
import org.qora.account.PrivateKeyAccount;
import org.qora.account.PublicKeyAccount;
import org.qora.data.network.OnlineAccountData;
import org.qora.network.message.GetOnlineAccountsMessage;
import org.qora.network.message.Message;
import org.qora.network.message.OnlineAccountsMessage;
import org.qora.repository.DataException;
import org.qora.test.common.Common;
import org.qora.test.common.FakePeer;
import org.qora.utils.ByteArray;

import com.google.common.primitives.Longs;

public class OnlineTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	private static final int MAX_PEERS = 100;
	private static final int MAX_RUNNING_PEERS = 20;
	private static final boolean LOG_CONNECTION_CHANGES = false;
	private static final boolean LOG_ACCOUNT_CHANGES = true;
	private static final boolean GET_ONLINE_UNICAST_NOT_BROADCAST = false;
	private static final long ONLINE_TIMESTAMP_MODULUS = 5 * 60 * 1000;

	private static List<PrivateKeyAccount> allKnownAccounts;
	private static final Random random = new Random();

	static class OnlinePeer extends FakePeer {
		private static final long LAST_SEEN_EXPIRY_PERIOD = 6 * 60 * 1000;
		private static final long ONLINE_REFRESH_INTERVAL = 4 * 60 * 1000;
		private static final int MAX_CONNECTED_PEERS = 5;

		private final PrivateKeyAccount account;

		private List<OnlineAccountData> onlineAccounts;
		private long nextOnlineRefresh = 0;

		public OnlinePeer(int id, PrivateKeyAccount account) {
			super(id);

			this.account = account;

			this.onlineAccounts = Collections.synchronizedList(new ArrayList<>());
		}

		@Override
		protected void processMessage(FakePeer peer, Message message) throws InterruptedException {
			switch (message.getType()) {
				case GET_ONLINE_ACCOUNTS: {
					GetOnlineAccountsMessage getOnlineAccountsMessage = (GetOnlineAccountsMessage) message;

					List<OnlineAccountData> excludeAccounts = getOnlineAccountsMessage.getOnlineAccounts();

					// Send online accounts info, excluding entries with matching timestamp & public key from excludeAccounts
					List<OnlineAccountData> accountsToSend;
					synchronized (this.onlineAccounts) {
						accountsToSend = new ArrayList<>(this.onlineAccounts);
					}

					Iterator<OnlineAccountData> iterator = accountsToSend.iterator();

					SEND_ITERATOR:
					while (iterator.hasNext()) {
						OnlineAccountData onlineAccount = iterator.next();

						for (int i = 0; i < excludeAccounts.size(); ++i) {
							OnlineAccountData excludeAccount = excludeAccounts.get(i);

							if (onlineAccount.getTimestamp() == excludeAccount.getTimestamp() && Arrays.equals(onlineAccount.getPublicKey(), excludeAccount.getPublicKey())) {
								iterator.remove();
								continue SEND_ITERATOR;
							}
						}
					}

					Message onlineAccountsMessage = new OnlineAccountsMessage(accountsToSend);
					this.send(peer, onlineAccountsMessage);

					if (LOG_ACCOUNT_CHANGES)
						System.out.println(String.format("[%d] sent %d of our %d online accounts to %d", this.getId(), accountsToSend.size(), onlineAccounts.size(), peer.getId()));

					break;
				}

				case ONLINE_ACCOUNTS: {
					OnlineAccountsMessage onlineAccountsMessage = (OnlineAccountsMessage) message;

					List<OnlineAccountData> onlineAccounts = onlineAccountsMessage.getOnlineAccounts();

					if (LOG_ACCOUNT_CHANGES)
						System.out.println(String.format("[%d] received %d online accounts from %d", this.getId(), onlineAccounts.size(), peer.getId()));

					for (OnlineAccountData onlineAccount : onlineAccounts)
						verifyAndAddAccount(onlineAccount);

					break;
				}

				default:
					break;
			}
		}

		private void verifyAndAddAccount(OnlineAccountData onlineAccount) {
			// we would check timestamp is 'recent' here

			// Verify
			byte[] data = Longs.toByteArray(onlineAccount.getTimestamp());
			PublicKeyAccount otherAccount = new PublicKeyAccount(null, onlineAccount.getPublicKey());
			if (!otherAccount.verify(onlineAccount.getSignature(), data)) {
				System.out.println(String.format("[%d] rejecting invalid online account %s", this.getId(), otherAccount.getAddress()));
				return;
			}

			ByteArray publicKeyBA = new ByteArray(onlineAccount.getPublicKey());

			synchronized (this.onlineAccounts) {
				OnlineAccountData existingAccount = this.onlineAccounts.stream().filter(account -> new ByteArray(account.getPublicKey()).equals(publicKeyBA)).findFirst().orElse(null);

				if (existingAccount != null) {
					if (existingAccount.getTimestamp() < onlineAccount.getTimestamp()) {
						this.onlineAccounts.remove(existingAccount);

						if (LOG_ACCOUNT_CHANGES)
							System.out.println(String.format("[%d] updated online account %s with timestamp %d (was %d)", this.getId(), otherAccount.getAddress(), onlineAccount.getTimestamp(), existingAccount.getTimestamp()));
					} else {
						if (LOG_ACCOUNT_CHANGES)
							System.out.println(String.format("[%d] ignoring existing online account %s", this.getId(), otherAccount.getAddress()));

						return;
					}
				} else {
					if (LOG_ACCOUNT_CHANGES)
						System.out.println(String.format("[%d] added online account %s with timestamp %d", this.getId(), otherAccount.getAddress(), onlineAccount.getTimestamp()));
				}

				this.onlineAccounts.add(onlineAccount);
			}
		}

		@Override
		protected void performIdleTasks() {
			final long now = System.currentTimeMillis();

			// Expire old entries
			final long cutoffThreshold = now - LAST_SEEN_EXPIRY_PERIOD;
			synchronized (this.onlineAccounts) {
				Iterator<OnlineAccountData> iterator = this.onlineAccounts.iterator();
				while (iterator.hasNext()) {
					OnlineAccountData onlineAccount = iterator.next();

					if (onlineAccount.getTimestamp() < cutoffThreshold) {
						iterator.remove();

						if (LOG_ACCOUNT_CHANGES) {
							PublicKeyAccount otherAccount = new PublicKeyAccount(null, onlineAccount.getPublicKey());
							System.out.println(String.format("[%d] removed expired online account %s with timestamp %d", this.getId(), otherAccount.getAddress(), onlineAccount.getTimestamp()));
						}
					}
				}
			}

			// Request data from another peer
			Message message;
			synchronized (this.onlineAccounts) {
				message = new GetOnlineAccountsMessage(this.onlineAccounts);
			}

			if (GET_ONLINE_UNICAST_NOT_BROADCAST) {
				FakePeer peer = this.pickRandomPeer();
				if (peer != null)
					this.send(peer, message);
			} else {
				this.broadcast(message);
			}

			// Refresh our onlineness?
			if (now >= this.nextOnlineRefresh) {
				this.nextOnlineRefresh = now + ONLINE_REFRESH_INTERVAL;
				refreshOnlineness();
			}

			// Log our online list
			synchronized (this.onlineAccounts) {
				System.out.println(String.format("[%d] Connections: %d, online accounts: %d", this.getId(), this.peers.size(), this.onlineAccounts.size()));
			}
		}

		private void refreshOnlineness() {
			// Broadcast signed timestamp
			final long timestamp = (System.currentTimeMillis() / ONLINE_TIMESTAMP_MODULUS) * ONLINE_TIMESTAMP_MODULUS;

			byte[] data = Longs.toByteArray(timestamp);
			byte[] signature = this.account.sign(data);
			byte[] publicKey = this.account.getPublicKey();

			// Our account is online
			OnlineAccountData onlineAccount = new OnlineAccountData(timestamp, signature, publicKey);
			synchronized (this.onlineAccounts) {
				this.onlineAccounts.removeIf(account -> account.getPublicKey() == this.account.getPublicKey());
				this.onlineAccounts.add(onlineAccount);
			}

			Message message = new OnlineAccountsMessage(Arrays.asList(onlineAccount));
			this.broadcast(message);

			if (LOG_ACCOUNT_CHANGES)
				System.out.println(String.format("[%d] broadcasted online account %s with timestamp %d", this.getId(), this.account.getAddress(), timestamp));
		}

		@Override
		public void connect(FakePeer otherPeer) {
			int totalPeers;
			synchronized (this.peers) {
				totalPeers = this.peers.size();
			}

			if (totalPeers >= MAX_CONNECTED_PEERS)
					return;

			super.connect(otherPeer);

			if (LOG_CONNECTION_CHANGES)
				System.out.println(String.format("[%d] Connected to peer %d, total peers: %d", this.getId(), otherPeer.getId(), totalPeers + 1));
		}

		public void randomDisconnect() {
			FakePeer peer;
			int totalPeers;

			synchronized (this.peers) {
				peer = this.pickRandomPeer();
				if (peer == null)
					return;

				totalPeers = this.peers.size();
			}

			this.disconnect(peer);

			if (LOG_CONNECTION_CHANGES)
				System.out.println(String.format("[%d] Disconnected peer %d, total peers: %d", this.getId(), peer.getId(), totalPeers - 1));
		}
	}

	@Test
	public void testOnlineness() throws InterruptedException {
		allKnownAccounts = new ArrayList<>();

		List<OnlinePeer> allPeers = new ArrayList<>();

		for (int i = 0; i < MAX_PEERS; ++i) {
			byte[] seed = new byte[32];
			random.nextBytes(seed);
			PrivateKeyAccount account = new PrivateKeyAccount(null, seed);

			allKnownAccounts.add(account);

			OnlinePeer peer = new OnlinePeer(i, account);
			allPeers.add(peer);
		}

		// Start up some peers
		List<OnlinePeer> runningPeers = new ArrayList<>();
		ExecutorService peerExecutor = Executors.newCachedThreadPool();

		for (int c = 0; c < MAX_RUNNING_PEERS; ++c) {
			OnlinePeer newPeer;
			do {
				int i = random.nextInt(allPeers.size());
				newPeer = allPeers.get(i);
			} while (runningPeers.contains(newPeer));

			runningPeers.add(newPeer);
			peerExecutor.execute(newPeer);
		}

		// Randomly connect/disconnect peers
		while (true) {
			int i = random.nextInt(runningPeers.size());
			OnlinePeer peer = runningPeers.get(i);

			if ((random.nextInt() & 0xf) != 0) {
				// Connect
				OnlinePeer otherPeer;
				do {
					int j = random.nextInt(runningPeers.size());
					otherPeer = runningPeers.get(j);
				} while (otherPeer == peer);

				peer.connect(otherPeer);
			} else {
				peer.randomDisconnect();
			}

			Thread.sleep(100);
		}
	}

}
