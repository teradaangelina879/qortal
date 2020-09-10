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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

/** ElectrumX network support for querying Bitcoin-related info like block headers, transaction outputs, etc. */
public class ElectrumX {

	private static final Logger LOGGER = LogManager.getLogger(ElectrumX.class);
	private static final Random RANDOM = new Random();

	private static final double MIN_PROTOCOL_VERSION = 1.2;

	private static final int DEFAULT_TCP_PORT = 50001;
	private static final int DEFAULT_SSL_PORT = 50002;

	private static final int BLOCK_HEADER_LENGTH = 80;

	private static final String MAIN_GENESIS_HASH = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f";
	private static final String TEST3_GENESIS_HASH = "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943";
	// We won't know REGTEST (i.e. local) genesis block hash

	// "message": "daemon error: DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})"
	private static final Pattern DAEMON_ERROR_REGEX = Pattern.compile("DaemonError\\(\\{.*'code': ?(-?[0-9]+).*\\}\\)\\z"); // Capture 'code' inside curly-brace content

	// Key: Bitcoin network (e.g. "MAIN", "TEST3", "REGTEST"), value: ElectrumX instance
	private static final Map<String, ElectrumX> instances = new HashMap<>();

	private static class Server {
		String hostname;

		enum ConnectionType { TCP, SSL }
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
	private List<Server> remainingServers = new ArrayList<>();

	private String expectedGenesisHash;
	private Server currentServer;
	private Socket socket;
	private Scanner scanner;
	private int nextId = 1;

	// Constructors

	private ElectrumX(String bitcoinNetwork) {
		switch (bitcoinNetwork) {
			case "MAIN":
				this.expectedGenesisHash = MAIN_GENESIS_HASH;

				this.servers.addAll(Arrays.asList(
						// Servers chosen on NO BASIS WHATSOEVER from various sources!
						new Server("enode.duckdns.org", Server.ConnectionType.SSL, 50002),
						new Server("electrumx.ml", Server.ConnectionType.SSL, 50002),
						new Server("electrum.bitkoins.nl", Server.ConnectionType.SSL, 50512),
						new Server("btc.electroncash.dk", Server.ConnectionType.SSL, 60002),
						new Server("electrumx.electricnewyear.net", Server.ConnectionType.SSL, 50002),
						new Server("dxm.no-ip.biz", Server.ConnectionType.TCP, 50001),
						new Server("kirsche.emzy.de", Server.ConnectionType.TCP, 50001),
						new Server("2AZZARITA.hopto.org", Server.ConnectionType.TCP, 50001),
						new Server("xtrum.com", Server.ConnectionType.TCP, 50001),
						new Server("electrum.srvmin.network", Server.ConnectionType.TCP, 50001),
						new Server("electrumx.alexridevski.net", Server.ConnectionType.TCP, 50001),
						new Server("bitcoin.lukechilds.co", Server.ConnectionType.TCP, 50001),
						new Server("electrum.poiuty.com", Server.ConnectionType.TCP, 50001),
						new Server("horsey.cryptocowboys.net", Server.ConnectionType.TCP, 50001),
						new Server("electrum.emzy.de", Server.ConnectionType.TCP, 50001),
						new Server("electrum-server.ninja", Server.ConnectionType.TCP, 50081),
						new Server("bitcoin.electrumx.multicoin.co", Server.ConnectionType.TCP, 50001),
						new Server("esx.geekhosters.com", Server.ConnectionType.TCP, 50001),
						new Server("bitcoin.grey.pw", Server.ConnectionType.TCP, 50003),
						new Server("exs.ignorelist.com", Server.ConnectionType.TCP, 50001),
						new Server("electrum.coinext.com.br", Server.ConnectionType.TCP, 50001),
						new Server("bitcoin.aranguren.org", Server.ConnectionType.TCP, 50001),
						new Server("skbxmit.coinjoined.com", Server.ConnectionType.TCP, 50001),
						new Server("alviss.coinjoined.com", Server.ConnectionType.TCP, 50001),
						new Server("electrum2.privateservers.network", Server.ConnectionType.TCP, 50001),
						new Server("electrumx.schulzemic.net", Server.ConnectionType.TCP, 50001),
						new Server("bitcoins.sk", Server.ConnectionType.TCP, 56001),
						new Server("node.mendonca.xyz", Server.ConnectionType.TCP, 50001),
						new Server("bitcoin.aranguren.org", Server.ConnectionType.TCP, 50001)));
				break;

			case "TEST3":
				this.expectedGenesisHash = TEST3_GENESIS_HASH;

				this.servers.addAll(Arrays.asList(
						new Server("electrum.blockstream.info", Server.ConnectionType.TCP, 60001),
						new Server("electrum.blockstream.info", Server.ConnectionType.SSL, 60002),
						new Server("electrumx-test.1209k.com", Server.ConnectionType.SSL, 50002),
						new Server("testnet.qtornado.com", Server.ConnectionType.TCP, 51001),
						new Server("testnet.qtornado.com", Server.ConnectionType.SSL, 51002),
						new Server("testnet.aranguren.org", Server.ConnectionType.TCP, 51001),
						new Server("testnet.aranguren.org", Server.ConnectionType.SSL, 51002),
						new Server("testnet.hsmiths.com", Server.ConnectionType.SSL, 53012)));
				break;

			case "REGTEST":
				this.expectedGenesisHash = null;

				this.servers.addAll(Arrays.asList(
						new Server("localhost", Server.ConnectionType.TCP, DEFAULT_TCP_PORT),
						new Server("localhost", Server.ConnectionType.SSL, DEFAULT_SSL_PORT)));
				break;

			default:
				throw new IllegalArgumentException(String.format("Bitcoin network '%s' unknown", bitcoinNetwork));
		}

		LOGGER.debug(() -> String.format("Starting ElectrumX support for %s Bitcoin network", bitcoinNetwork));
	}

	/** Returns ElectrumX instance linked to passed Bitcoin network, one of "MAIN", "TEST3" or "REGTEST". */
	public static synchronized ElectrumX getInstance(String bitcoinNetwork) {
		if (!instances.containsKey(bitcoinNetwork))
			instances.put(bitcoinNetwork, new ElectrumX(bitcoinNetwork));

		return instances.get(bitcoinNetwork);
	}

	// Methods for use by other classes

	/**
	 * Returns current blockchain height.
	 * <p>
	 * @throws BitcoinException if error occurs
	 */
	public int getCurrentHeight() throws BitcoinException {
		Object blockObj = this.rpc("blockchain.headers.subscribe");
		if (!(blockObj instanceof JSONObject))
			throw new BitcoinException.NetworkException("Unexpected output from ElectrumX blockchain.headers.subscribe RPC");

		JSONObject blockJson = (JSONObject) blockObj;

		Object heightObj = blockJson.get("height");

		if (!(heightObj instanceof Long))
			throw new BitcoinException.NetworkException("Missing/invalid 'height' in JSON from ElectrumX blockchain.headers.subscribe RPC");

		return ((Long) heightObj).intValue();
	}

	/**
	 * Returns list of raw block headers, starting from <tt>startHeight</tt> inclusive.
	 * <p>
	 * @throws BitcoinException if error occurs
	 */
	public List<byte[]> getBlockHeaders(int startHeight, long count) throws BitcoinException {
		Object blockObj = this.rpc("blockchain.block.headers", startHeight, count);
		if (!(blockObj instanceof JSONObject))
			throw new BitcoinException.NetworkException("Unexpected output from ElectrumX blockchain.block.headers RPC");

		JSONObject blockJson = (JSONObject) blockObj;

		Object countObj = blockJson.get("count");
		Object hexObj = blockJson.get("hex");

		if (!(countObj instanceof Long) || !(hexObj instanceof String))
			throw new BitcoinException.NetworkException("Missing/invalid 'count' or 'hex' entries in JSON from ElectrumX blockchain.block.headers RPC");

		Long returnedCount = (Long) countObj;
		String hex = (String) hexObj;

		byte[] raw = HashCode.fromString(hex).asBytes();
		if (raw.length != returnedCount * BLOCK_HEADER_LENGTH)
			throw new BitcoinException.NetworkException("Unexpected raw header length in JSON from ElectrumX blockchain.block.headers RPC");

		List<byte[]> rawBlockHeaders = new ArrayList<>(returnedCount.intValue());
		for (int i = 0; i < returnedCount; ++i)
			rawBlockHeaders.add(Arrays.copyOfRange(raw, i * BLOCK_HEADER_LENGTH, (i + 1) * BLOCK_HEADER_LENGTH));

		return rawBlockHeaders;
	}

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if script unknown
	 * @throws BitcoinException if there was an error
	 */
	public long getConfirmedBalance(byte[] script) throws BitcoinException {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object balanceObj = this.rpc("blockchain.scripthash.get_balance", HashCode.fromBytes(scriptHash).toString());
		if (!(balanceObj instanceof JSONObject))
			throw new BitcoinException.NetworkException("Unexpected output from ElectrumX blockchain.scripthash.get_balance RPC");

		JSONObject balanceJson = (JSONObject) balanceObj;

		Object confirmedBalanceObj = balanceJson.get("confirmed");

		if (!(confirmedBalanceObj instanceof Long))
			throw new BitcoinException.NetworkException("Missing confirmed balance from ElectrumX blockchain.scripthash.get_balance RPC");

		return (Long) balanceJson.get("confirmed");
	}

	/**
	 * Returns list of unspent outputs pertaining to passed payment script.
	 * <p>
	 * @return list of unspent outputs, or empty list if script unknown
	 * @throws BitcoinException if there was an error.
	 */
	public List<UnspentOutput> getUnspentOutputs(byte[] script, boolean includeUnconfirmed) throws BitcoinException {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object unspentJson = this.rpc("blockchain.scripthash.listunspent", HashCode.fromBytes(scriptHash).toString());
		if (!(unspentJson instanceof JSONArray))
			throw new BitcoinException("Expected array output from ElectrumX blockchain.scripthash.listunspent RPC");

		List<UnspentOutput> unspentOutputs = new ArrayList<>();
		for (Object rawUnspent : (JSONArray) unspentJson) {
			JSONObject unspent = (JSONObject) rawUnspent;

			int height = ((Long) unspent.get("height")).intValue();
			// We only want unspent outputs from confirmed transactions (and definitely not mempool duplicates with height 0)
			if (!includeUnconfirmed && height <= 0)
				continue;

			byte[] txHash = HashCode.fromString((String) unspent.get("tx_hash")).asBytes();
			int outputIndex = ((Long) unspent.get("tx_pos")).intValue();
			long value = (Long) unspent.get("value");

			unspentOutputs.add(new UnspentOutput(txHash, outputIndex, height, value));
		}

		return unspentOutputs;
	}

	/**
	 * Returns raw transaction for passed transaction hash.
	 * <p>
	 * @throws BitcoinException.NotFoundException if transaction not found
	 * @throws BitcoinException if error occurs
	 */
	public byte[] getRawTransaction(byte[] txHash) throws BitcoinException {
		Object rawTransactionHex;
		try {
			rawTransactionHex = this.rpc("blockchain.transaction.get", HashCode.fromBytes(txHash).toString());
		} catch (BitcoinException.NetworkException e) {
			// DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})
			if (Integer.valueOf(-5).equals(e.getDaemonErrorCode()))
				throw new BitcoinException.NotFoundException(e.getMessage());

			throw e;
		}

		if (!(rawTransactionHex instanceof String))
			throw new BitcoinException.NetworkException("Expected hex string as raw transaction from ElectrumX blockchain.transaction.get RPC");

		return HashCode.fromString((String) rawTransactionHex).asBytes();
	}

	/**
	 * Returns transaction info for passed transaction hash.
	 * <p>
	 * @throws BitcoinException.NotFoundException if transaction not found
	 * @throws BitcoinException if error occurs
	 */
	public BitcoinTransaction getTransaction(String txHash) throws BitcoinException {
		Object transactionObj;
		try {
			transactionObj = this.rpc("blockchain.transaction.get", txHash, true);
		} catch (BitcoinException.NetworkException e) {
			// DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})
			if (Integer.valueOf(-5).equals(e.getDaemonErrorCode()))
				throw new BitcoinException.NotFoundException(e.getMessage());

			throw e;
		}

		if (!(transactionObj instanceof JSONObject))
			throw new BitcoinException.NetworkException("Expected JSONObject as response from ElectrumX blockchain.transaction.get RPC");

		JSONObject transactionJson = (JSONObject) transactionObj;

		Object inputsObj = transactionJson.get("vin");
		if (!(inputsObj instanceof JSONArray))
			throw new BitcoinException.NetworkException("Expected JSONArray for 'vin' from ElectrumX blockchain.transaction.get RPC");

		Object outputsObj = transactionJson.get("vout");
		if (!(outputsObj instanceof JSONArray))
			throw new BitcoinException.NetworkException("Expected JSONArray for 'vout' from ElectrumX blockchain.transaction.get RPC");

		try {
			int size = ((Long) transactionJson.get("size")).intValue();
			int locktime = ((Long) transactionJson.get("locktime")).intValue();

			// Timestamp might not be present, e.g. for unconfirmed transaction
			Object timeObj = transactionJson.get("time");
			Integer timestamp = timeObj != null
					? ((Long) timeObj).intValue()
					: null;

			List<BitcoinTransaction.Input> inputs = new ArrayList<>();
			for (Object inputObj : (JSONArray) inputsObj) {
				JSONObject inputJson = (JSONObject) inputObj;

				String scriptSig = (String) ((JSONObject) inputJson.get("scriptSig")).get("hex");
				int sequence = ((Long) inputJson.get("sequence")).intValue();
				String outputTxHash = (String) inputJson.get("txid");
				int outputVout = ((Long) inputJson.get("vout")).intValue();

				inputs.add(new BitcoinTransaction.Input(scriptSig, sequence, outputTxHash, outputVout));
			}

			List<BitcoinTransaction.Output> outputs = new ArrayList<>();
			for (Object outputObj : (JSONArray) outputsObj) {
				JSONObject outputJson = (JSONObject) outputObj;

				String scriptPubKey = (String) ((JSONObject) outputJson.get("scriptPubKey")).get("hex");
				long value = (long) (((Double) outputJson.get("value")) * 1e8);

				outputs.add(new BitcoinTransaction.Output(scriptPubKey, value));
			}

			return new BitcoinTransaction(txHash, size, locktime, timestamp, inputs, outputs);
		} catch (NullPointerException | ClassCastException e) {
			// Unexpected / invalid response from ElectrumX server
		}

		throw new BitcoinException.NetworkException("Unexpected JSON format from ElectrumX blockchain.transaction.get RPC");
	}

	/**
	 * Returns list of transactions, relating to passed payment script.
	 * <p>
	 * @return list of related transactions, or empty list if script unknown
	 * @throws BitcoinException if error occurs
	 */
	public List<TransactionHash> getAddressTransactions(byte[] script, boolean includeUnconfirmed) throws BitcoinException {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object transactionsJson = this.rpc("blockchain.scripthash.get_history", HashCode.fromBytes(scriptHash).toString());
		if (!(transactionsJson instanceof JSONArray))
			throw new BitcoinException.NetworkException("Expected array output from ElectrumX blockchain.scripthash.get_history RPC");

		List<TransactionHash> transactionHashes = new ArrayList<>();

		for (Object rawTransactionInfo : (JSONArray) transactionsJson) {
			JSONObject transactionInfo = (JSONObject) rawTransactionInfo;

			Long height = (Long) transactionInfo.get("height");
			if (!includeUnconfirmed && (height == null || height == 0))
				// We only want confirmed transactions
				continue;

			String txHash = (String) transactionInfo.get("tx_hash");

			transactionHashes.add(new TransactionHash(height.intValue(), txHash));
		}

		return transactionHashes;
	}

	/**
	 * Broadcasts raw transaction to Bitcoin network.
	 * <p>
	 * @throws BitcoinException if error occurs
	 */
	public void broadcastTransaction(byte[] transactionBytes) throws BitcoinException {
		Object rawBroadcastResult = this.rpc("blockchain.transaction.broadcast", HashCode.fromBytes(transactionBytes).toString());

		// We're expecting a simple string that is the transaction hash
		if (!(rawBroadcastResult instanceof String))
			throw new BitcoinException.NetworkException("Unexpected response from ElectrumX blockchain.transaction.broadcast RPC");
	}

	// Class-private utility methods

	/**
	 * Query current server for its list of peer servers, and return those we can parse.
	 * <p>
	 * @throws BitcoinException
	 * @throws ClassCastException to be handled by caller
	 */
	private Set<Server> serverPeersSubscribe() throws BitcoinException {
		Set<Server> newServers = new HashSet<>();

		Object peers = this.connectedRpc("server.peers.subscribe");

		for (Object rawPeer : (JSONArray) peers) {
			JSONArray peer = (JSONArray) rawPeer;
			if (peer.size() < 3)
				// We're expecting at least 3 fields for each peer entry: IP, hostname, features
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

					default:
						// e.g. could be 'v' for protocol version, or 'p' for pruning limit
						break;
				}

				if (connectionType == null)
					// We couldn't extract any peer connection info?
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

	/**
	 * Performs RPC call, with automatic reconnection to different server if needed.
	 * <p>
	 * @return "result" object from within JSON output
	 * @throws BitcoinException if server returns error or something goes wrong
	 */
	private synchronized Object rpc(String method, Object...params) throws BitcoinException {
		if (this.remainingServers.isEmpty())
			this.remainingServers.addAll(this.servers);

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

		// Failed to perform RPC - maybe lack of servers?
		throw new BitcoinException.NetworkException("Failed to perform Bitcoin RPC");
	}

	/** Returns true if we have, or create, a connection to an ElectrumX server. */
	private boolean haveConnection() throws BitcoinException {
		if (this.currentServer != null)
			return true;

		while (!this.remainingServers.isEmpty()) {
			Server server = this.remainingServers.remove(RANDOM.nextInt(this.remainingServers.size()));
			LOGGER.trace(() -> String.format("Connecting to %s", server));

			try {
				SocketAddress endpoint = new InetSocketAddress(server.hostname, server.port);
				int timeout = 5000; // ms

				this.socket = new Socket();
				this.socket.connect(endpoint, timeout);
				this.socket.setTcpNoDelay(true);

				if (server.connectionType == Server.ConnectionType.SSL) {
					SSLSocketFactory factory = TrustlessSSLSocketFactory.getSocketFactory();
					this.socket = factory.createSocket(this.socket, server.hostname, server.port, true);
				}

				this.scanner = new Scanner(this.socket.getInputStream());
				this.scanner.useDelimiter("\n");

				// Check connection is suitable by asking for server features, including genesis block hash
				JSONObject featuresJson = (JSONObject) this.connectedRpc("server.features");

				if (featuresJson == null || Double.valueOf((String) featuresJson.get("protocol_min")) < MIN_PROTOCOL_VERSION)
					continue;

				if (this.expectedGenesisHash != null && !((String) featuresJson.get("genesis_hash")).equals(this.expectedGenesisHash))
					continue;

				// Ask for more servers
				Set<Server> moreServers = serverPeersSubscribe();
				// Discard duplicate servers we already know
				moreServers.removeAll(this.servers);
				// Add to both lists
				this.remainingServers.addAll(moreServers);
				this.servers.addAll(moreServers);

				LOGGER.debug(() -> String.format("Connected to %s", server));
				this.currentServer = server;
				return true;
			} catch (IOException | BitcoinException | ClassCastException | NullPointerException e) {
				// Try another server...
				if (this.socket != null && !this.socket.isClosed())
					try {
						this.socket.close();
					} catch (IOException e1) {
						// We did try...
					}

				this.socket = null;
				this.scanner = null;
			}
		}

		return false;
	}

	/**
	 * Perform RPC using currently connected server.
	 * <p>
	 * @param method
	 * @param params
	 * @return response Object, or null if server fails to respond
	 * @throws BitcoinException if server returns error
	 */
	@SuppressWarnings("unchecked")
	private Object connectedRpc(String method, Object...params) throws BitcoinException {
		JSONObject requestJson = new JSONObject();
		requestJson.put("id", this.nextId++);
		requestJson.put("method", method);
		requestJson.put("jsonrpc", "2.0");

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
			// Unable to send, or receive -- try another server?
			return null;
		}

		LOGGER.trace(() -> String.format("Response: %s", response));

		if (response.isEmpty())
			// Empty response - try another server?
			return null;

		Object responseObj = JSONValue.parse(response);
		if (!(responseObj instanceof JSONObject))
			// Unexpected response - try another server?
			return null;

		JSONObject responseJson = (JSONObject) responseObj;

		Object errorObj = responseJson.get("error");
		if (errorObj != null) {
			if (!(errorObj instanceof JSONObject))
				throw new BitcoinException.NetworkException(String.format("Unexpected error response from ElectrumX RPC %s", method));

			JSONObject errorJson = (JSONObject) errorObj;

			Object messageObj = errorJson.get("message");

			if (!(messageObj instanceof String))
				throw new BitcoinException.NetworkException(String.format("Missing/invalid message in error response from ElectrumX RPC %s", method));

			String message = (String) messageObj;

			// Some error 'messages' are actually wrapped upstream bitcoind errors:
			// "message": "daemon error: DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})"
			// We want to detect these and extract the upstream error code for caller's use
			Matcher messageMatcher = DAEMON_ERROR_REGEX.matcher(message);
			if (messageMatcher.find())
				try {
					int daemonErrorCode = Integer.parseInt(messageMatcher.group(1));
					throw new BitcoinException.NetworkException(daemonErrorCode, message);
				} catch (NumberFormatException e) {
					// We couldn't parse the error code integer? Fall-through to generic exception...
				}

			throw new BitcoinException.NetworkException(message);
		}

		return responseJson.get("result");
	}

}
