package org.qortal.crosschain;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.TrustlessSSLSocketFactory;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;

public class ElectrumX {

	private static final Logger LOGGER = LogManager.getLogger(ElectrumX.class);
	private static final Random RANDOM = new Random();

	private static final int DEFAULT_TCP_PORT = 50001;
	private static final int DEFAULT_SSL_PORT = 50002;

	private static final int BLOCK_HEADER_LENGTH = 80;

	private static final Map<String, ElectrumX> instances = new HashMap<>();

	static class Server {
		String hostname;

		enum ConnectionType { TCP, SSL };
		ConnectionType connectionType;

		int port;

		public Server(String hostname, ConnectionType connectionType, int port) {
			this.hostname = hostname;
			this.connectionType = connectionType;
			this.port = port;
		}

		@Override
		public boolean equals(Object other) {
			if (other == this)
				return true;

			if (!(other instanceof Server))
				return false;

			Server otherServer = (Server) other;

			return this.connectionType == otherServer.connectionType
					&& this.port == otherServer.port
					&& this.hostname.equals(otherServer.hostname);
		}

		@Override
		public int hashCode() {
			return this.hostname.hashCode() ^ this.port;
		}

		@Override
		public String toString() {
			return String.format("%s:%s:%d", this.connectionType.name(), this.hostname, this.port);
		}
	}
	private Set<Server> servers = new HashSet<>();

	private Server currentServer;
	private Socket socket;
	private Scanner scanner;
	private int nextId = 1;

	// Constructors

	private ElectrumX(String bitcoinNetwork) {
		switch (bitcoinNetwork) {
			case "MAIN":
				servers.addAll(Arrays.asList());
				break;

			case "TEST3":
				servers.addAll(Arrays.asList(
						new Server("tn.not.fyi", Server.ConnectionType.TCP, 55001),
						new Server("tn.not.fyi", Server.ConnectionType.SSL, 55002),
						new Server("testnet.aranguren.org", Server.ConnectionType.TCP, 51001),
						new Server("testnet.aranguren.org", Server.ConnectionType.SSL, 51002),
						new Server("testnet.hsmiths.com", Server.ConnectionType.SSL, 53012)));
				break;

			case "REGTEST":
				servers.addAll(Arrays.asList(
						new Server("localhost", Server.ConnectionType.TCP, DEFAULT_TCP_PORT),
						new Server("localhost", Server.ConnectionType.SSL, DEFAULT_SSL_PORT)));
				break;

			default:
				throw new IllegalArgumentException(String.format("Bitcoin network '%s' unknown", bitcoinNetwork));
		}

		LOGGER.debug(() -> String.format("Starting ElectrumX support for %s Bitcoin network", bitcoinNetwork));
		rpc("server.banner");
	}

	public static synchronized ElectrumX getInstance(String bitcoinNetwork) {
		if (!instances.containsKey(bitcoinNetwork))
			instances.put(bitcoinNetwork, new ElectrumX(bitcoinNetwork));

		return instances.get(bitcoinNetwork);
	}

	// Methods for use by other classes

	public Integer getCurrentHeight() {
		Object blockObj = this.rpc("blockchain.headers.subscribe");
		if (!(blockObj instanceof JSONObject))
			return null;

		JSONObject blockJson = (JSONObject) blockObj;

		if (!blockJson.containsKey("height"))
			return null;

		return ((Long) blockJson.get("height")).intValue();
	}

	public List<byte[]> getBlockHeaders(int startHeight, long count) {
		Object blockObj = this.rpc("blockchain.block.headers", startHeight, count);
		if (!(blockObj instanceof JSONObject))
			return null;

		JSONObject blockJson = (JSONObject) blockObj;

		if (!blockJson.containsKey("count") || !blockJson.containsKey("hex"))
			return null;

		Long returnedCount = (Long) blockJson.get("count");
		String hex = (String) blockJson.get("hex");

		byte[] raw = HashCode.fromString(hex).asBytes();
		if (raw.length != returnedCount * BLOCK_HEADER_LENGTH)
			return null;

		List<byte[]> rawBlockHeaders = new ArrayList<>(returnedCount.intValue());
		for (int i = 0; i < returnedCount; ++i)
			rawBlockHeaders.add(Arrays.copyOfRange(raw, i * BLOCK_HEADER_LENGTH, (i + 1) * BLOCK_HEADER_LENGTH));

		return rawBlockHeaders;
	}

	public Long getBalance(byte[] script) {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object balanceObj = this.rpc("blockchain.scripthash.get_balance", HashCode.fromBytes(scriptHash).toString());
		if (!(balanceObj instanceof JSONObject))
			return null;

		JSONObject balanceJson = (JSONObject) balanceObj;

		if (!balanceJson.containsKey("confirmed"))
			return null;

		return (Long) balanceJson.get("confirmed");
	}

	public static class UnspentOutput {
		public final byte[] hash;
		public final int index;
		public final int height;
		public final long value;

		public UnspentOutput(byte[] hash, int index, int height, long value) {
			this.hash = hash;
			this.index = index;
			this.height = height;
			this.value = value;
		}
	}

	public List<UnspentOutput> getUnspentOutputs(byte[] script) {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object unspentJson = this.rpc("blockchain.scripthash.listunspent", HashCode.fromBytes(scriptHash).toString());
		if (!(unspentJson instanceof JSONArray))
			return null;

		List<UnspentOutput> unspentOutputs = new ArrayList<>();
		for (Object rawUnspent : (JSONArray) unspentJson) {
			JSONObject unspent = (JSONObject) rawUnspent;

			byte[] txHash = HashCode.fromString((String) unspent.get("tx_hash")).asBytes();
			int outputIndex = ((Long) unspent.get("tx_pos")).intValue();
			int height = ((Long) unspent.get("height")).intValue();
			long value = (Long) unspent.get("value");

			unspentOutputs.add(new UnspentOutput(txHash, outputIndex, height, value));
		}

		return unspentOutputs;
	}

	public byte[] getRawTransaction(byte[] txHash) {
		Object rawTransactionHex = this.rpc("blockchain.transaction.get", HashCode.fromBytes(txHash).toString());
		if (!(rawTransactionHex instanceof String))
			return null;

		return HashCode.fromString((String) rawTransactionHex).asBytes();
	}

	/** Returns list of raw transactions. */
	public List<byte[]> getAddressTransactions(byte[] script) {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object transactionsJson = this.rpc("blockchain.scripthash.get_history", HashCode.fromBytes(scriptHash).toString());
		if (!(transactionsJson instanceof JSONArray))
			return null;

		List<byte[]> rawTransactions = new ArrayList<>();

		for (Object rawTransactionInfo : (JSONArray) transactionsJson) {
			JSONObject transactionInfo = (JSONObject) rawTransactionInfo;

			// We only want confirmed transactions
			if (!transactionInfo.containsKey("height"))
				continue;

			String txHash = (String) transactionInfo.get("tx_hash");
			String rawTransactionHex = (String) this.rpc("blockchain.transaction.get", txHash);
			if (rawTransactionHex == null)
				return null;

			rawTransactions.add(HashCode.fromString(rawTransactionHex).asBytes());
		}

		return rawTransactions;
	}

	public boolean broadcastTransaction(byte[] transactionBytes) {
		Object rawBroadcastResult = this.rpc("blockchain.transaction.broadcast", HashCode.fromBytes(transactionBytes).toString());
		if (rawBroadcastResult == null)
			return false;

		// If result is a String, then it is simply transaction hash.
		// Otherwise result is JSON and probably contains error info instead.
		return rawBroadcastResult instanceof String;
	}

	// Class-private utility methods

	private Set<Server> serverPeersSubscribe() {
		Set<Server> newServers = new HashSet<>();

		Object peers = this.connectedRpc("server.peers.subscribe");
		if (!(peers instanceof JSONArray))
			return newServers;

		for (Object rawPeer : (JSONArray) peers) {
			JSONArray peer = (JSONArray) rawPeer;
			if (peer.size() < 3)
				continue;

			String hostname = (String) peer.get(1);
			JSONArray features = (JSONArray) peer.get(2);

			for (Object rawFeature : features) {
				String feature = (String) rawFeature;
				Server.ConnectionType connectionType = null;
				int port = -1;

				switch (feature.charAt(0)) {
					case 's':
						connectionType = Server.ConnectionType.SSL;
						port = DEFAULT_SSL_PORT;
						break;

					case 't':
						connectionType = Server.ConnectionType.TCP;
						port = DEFAULT_TCP_PORT;
						break;
				}

				if (connectionType == null)
					continue;

				// Possible non-default port?
				if (feature.length() > 1)
					try {
						port = Integer.parseInt(feature.substring(1));
					} catch (NumberFormatException e) {
						// no good
						continue; // for-loop above
					}

				Server newServer = new Server(hostname, connectionType, port);
				newServers.add(newServer);
			}
		}

		return newServers;
	}

	private synchronized Object rpc(String method, Object...params) {
		while (haveConnection()) {
			Object response = connectedRpc(method, params);
			if (response != null)
				return response;

			this.currentServer = null;
			try {
				this.socket.close();
			} catch (IOException e) {
				/* ignore */
			}
			this.scanner = null;
		}

		return null;
	}

	private boolean haveConnection() {
		if (this.currentServer != null)
			return true;

		List<Server> remainingServers = new ArrayList<>(this.servers);

		while (!remainingServers.isEmpty()) {
			Server server = remainingServers.remove(RANDOM.nextInt(remainingServers.size()));
			LOGGER.trace(() -> String.format("Connecting to %s", server));

			try {
				SocketAddress endpoint = new InetSocketAddress(server.hostname, server.port);
				int timeout = 5000; // ms

				this.socket = new Socket();
				this.socket.connect(endpoint, timeout);
				this.socket.setTcpNoDelay(true);

				if (server.connectionType == Server.ConnectionType.SSL) {
					SSLSocketFactory factory = TrustlessSSLSocketFactory.getSocketFactory();
					this.socket = (SSLSocket) factory.createSocket(this.socket, server.hostname, server.port, true);
				}

				this.scanner = new Scanner(this.socket.getInputStream());
				this.scanner.useDelimiter("\n");

				// Check connection works by asking for more servers
				Set<Server> moreServers = serverPeersSubscribe();
				moreServers.removeAll(this.servers);
				remainingServers.addAll(moreServers);
				this.servers.addAll(moreServers);

				LOGGER.debug(() -> String.format("Connected to %s", server));
				this.currentServer = server;
				return true;
			} catch (IOException e) {
				// Try another server...
				this.socket = null;
				this.scanner = null;
			}
		}

		return false;
	}

	@SuppressWarnings("unchecked")
	private Object connectedRpc(String method, Object...params) {
		JSONObject requestJson = new JSONObject();
		requestJson.put("id", this.nextId++);
		requestJson.put("method", method);

		JSONArray requestParams = new JSONArray();
		requestParams.addAll(Arrays.asList(params));
		requestJson.put("params", requestParams);

		String request = requestJson.toJSONString() + "\n";
		LOGGER.trace(() -> String.format("Request: %s", request));

		final String response;

		try {
			this.socket.getOutputStream().write(request.getBytes());
			response = scanner.next();
		} catch (IOException | NoSuchElementException e) {
			return null;
		}

		LOGGER.trace(() -> String.format("Response: %s", response));

		if (response.isEmpty())
			return null;

		Object responseObj = JSONValue.parse(response);
		if (!(responseObj instanceof JSONObject))
			return null;

		JSONObject responseJson = (JSONObject) responseObj;

		return responseJson.get("result");
	}

}
