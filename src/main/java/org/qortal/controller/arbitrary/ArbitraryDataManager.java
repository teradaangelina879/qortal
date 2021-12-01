package org.qortal.controller.arbitrary;

import java.security.SecureRandom;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.controller.Controller;
import org.qortal.data.network.ArbitraryPeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.list.ResourceListManager;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataFileChunk;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

public class ArbitraryDataManager extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataManager.class);
	private static final List<TransactionType> ARBITRARY_TX_TYPE = Arrays.asList(TransactionType.ARBITRARY);

	private static final long ARBITRARY_REQUEST_TIMEOUT = 5 * 1000L; // ms

	private static ArbitraryDataManager instance;
	private final Object peerDataLock = new Object();

	private volatile boolean isStopping = false;

	/**
	 * Map of recent requests for ARBITRARY transaction data file lists.
	 * <p>
	 * Key is original request's message ID<br>
	 * Value is Triple&lt;transaction signature in base58, first requesting peer, first request's timestamp&gt;
	 * <p>
	 * If peer is null then either:<br>
	 * <ul>
	 * <li>we are the original requesting peer</li>
	 * <li>we have already sent data payload to original requesting peer.</li>
	 * </ul>
	 * If signature is null then we have already received the file list and either:<br>
	 * <ul>
	 * <li>we are the original requesting peer and have processed it</li>
	 * <li>we have forwarded the file list</li>
	 * </ul>
	 */
	public Map<Integer, Triple<String, Peer, Long>> arbitraryDataFileListRequests = Collections.synchronizedMap(new HashMap<>());

	/**
	 * Map to keep track of in progress arbitrary data file requests
	 */
	private Map<String, Long> arbitraryDataFileRequests = Collections.synchronizedMap(new HashMap<>());

	/**
	 * Map to keep track of in progress arbitrary data signature requests
	 * Key: string - the signature encoded in base58
	 * Value: Triple<networkBroadcastCount, directPeerRequestCount, lastAttemptTimestamp>
	 */
	private Map<String, Triple<Integer, Integer, Long>> arbitraryDataSignatureRequests = Collections.synchronizedMap(new HashMap<>());

	/**
	 * Map to keep track of cached arbitrary transaction resources.
	 * When an item is present in this list with a timestamp in the future, we won't invalidate
	 * its cache when serving that data. This reduces the amount of database lookups that are needed.
	 */
	private Map<String, Long> arbitraryDataCachedResources = Collections.synchronizedMap(new HashMap<>());

	/**
	 * The amount of time to cache a data resource before it is invalidated
	 */
	private static long ARBITRARY_DATA_CACHE_TIMEOUT = 60 * 60 * 1000L; // 60 minutes



	private ArbitraryDataManager() {
	}

	public static ArbitraryDataManager getInstance() {
		if (instance == null)
			instance = new ArbitraryDataManager();

		return instance;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("Arbitrary Data Manager");

		try {
			while (!isStopping) {
				Thread.sleep(2000);

				List<Peer> peers = Network.getInstance().getHandshakedPeers();

				// Disregard peers that have "misbehaved" recently
				peers.removeIf(Controller.hasMisbehaved);

				// Don't fetch data if we don't have enough up-to-date peers
				if (peers.size() < Settings.getInstance().getMinBlockchainPeers()) {
					continue;
				}

				// Fetch data according to storage policy
				switch (Settings.getInstance().getStoragePolicy()) {
					case FOLLOWED:
					case FOLLOWED_AND_VIEWED:
						this.processNames();
						break;

					case ALL:
						this.processAll();

					case NONE:
					case VIEWED:
					default:
						// Nothing to fetch in advance
						Thread.sleep(60000);
						break;
				}
			}
		} catch (InterruptedException e) {
			// Fall-through to exit thread...
		}
	}

	public void shutdown() {
		isStopping = true;
		this.interrupt();
	}

	private void processNames() {
		// Fetch latest list of followed names
		List<String> followedNames = ResourceListManager.getInstance().getStringsInList("followed", "names");
		if (followedNames == null || followedNames.isEmpty()) {
			return;
		}

		// Loop through the names in the list and fetch transactions for each
		for (String name : followedNames) {
			this.fetchAndProcessTransactions(name);
		}
	}

	private void processAll() {
		this.fetchAndProcessTransactions(null);
	}

	private void fetchAndProcessTransactions(String name) {
		ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();

		// Paginate queries when fetching arbitrary transactions
		final int limit = 100;
		int offset = 0;

		while (!isStopping) {

			// Any arbitrary transactions we want to fetch data for?
			try (final Repository repository = RepositoryManager.getRepository()) {
				List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null, ARBITRARY_TX_TYPE, null, name, null, ConfirmationStatus.BOTH, limit, offset, true);
				// LOGGER.info("Found {} arbitrary transactions at offset: {}, limit: {}", signatures.size(), offset, limit);
				if (signatures == null || signatures.isEmpty()) {
					offset = 0;
					break;
				}
				offset += limit;

				// Loop through signatures and remove ones we don't need to process
				Iterator iterator = signatures.iterator();
				while (iterator.hasNext()) {
					byte[] signature = (byte[]) iterator.next();

					ArbitraryTransaction arbitraryTransaction = fetchTransaction(repository, signature);
					if (arbitraryTransaction == null) {
						// Best not to process this one
						iterator.remove();
						continue;
					}
					ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) arbitraryTransaction.getTransactionData();

					// Skip transactions that we don't need to proactively store data for
					if (!storageManager.shouldPreFetchData(arbitraryTransactionData)) {
						iterator.remove();
						continue;
					}

					// Remove transactions that we already have local data for
					if (hasLocalData(arbitraryTransaction)) {
						iterator.remove();
						continue;
					}
				}

				if (signatures.isEmpty()) {
					continue;
				}

				// Pick one at random
				final int index = new Random().nextInt(signatures.size());
				byte[] signature = signatures.get(index);

				if (signature == null) {
					continue;
				}

				// Check to see if we have had a more recent PUT
				ArbitraryTransactionData arbitraryTransactionData = ArbitraryTransactionUtils.fetchTransactionData(repository, signature);
				boolean hasMoreRecentPutTransaction = ArbitraryTransactionUtils.hasMoreRecentPutTransaction(repository, arbitraryTransactionData);
				if (hasMoreRecentPutTransaction) {
					// There is a more recent PUT transaction than the one we are currently processing.
					// When a PUT is issued, it replaces any layers that would have been there before.
					// Therefore any data relating to this older transaction is no longer needed and we
					// shouldn't fetch it from the network.
					continue;
				}

				// Ask our connected peers if they have files for this signature
				// This process automatically then fetches the files themselves if a peer is found
				fetchData(arbitraryTransactionData);

			} catch (DataException e) {
				LOGGER.error("Repository issue when fetching arbitrary transaction data", e);
			}
		}
	}

	private ArbitraryTransaction fetchTransaction(final Repository repository, byte[] signature) {
		try {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return null;

			return new ArbitraryTransaction(repository, transactionData);

		} catch (DataException e) {
			return null;
		}
	}

	private boolean hasLocalData(ArbitraryTransaction arbitraryTransaction) {
		try {
			return arbitraryTransaction.isDataLocal();

		} catch (DataException e) {
			LOGGER.error("Repository issue when checking arbitrary transaction's data is local", e);
			return true;
		}
	}

	private boolean hasLocalMetadata(ArbitraryTransactionData transactionData) {
		if (transactionData == null) {
			return false;
		}

		// Load hashes
		byte[] hash = transactionData.getData();
		byte[] metadataHash = transactionData.getMetadataHash();

		if (metadataHash == null) {
			// This transaction has no metadata, so we can treat it as local
			return true;
		}

		// Load data file(s)
		byte[] signature = transactionData.getSignature();
		try {
			ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, signature);
			arbitraryDataFile.setMetadataHash(metadataHash);

			return arbitraryDataFile.getMetadataFile().exists();
		}
		catch (DataException e) {
			// Assume not local
			return false;
		}
	}


	// Track file list lookups by signature

	private boolean shouldMakeFileListRequestForSignature(String signature58) {
		Triple<Integer, Integer, Long> request = arbitraryDataSignatureRequests.get(signature58);

		if (request == null) {
			// Not attempted yet
			return true;
		}

		// Extract the components
		Integer networkBroadcastCount = request.getA();
		// Integer directPeerRequestCount = request.getB();
		Long lastAttemptTimestamp = request.getC();

		if (lastAttemptTimestamp == null) {
			// Not attempted yet
			return true;
		}

		long timeSinceLastAttempt = NTP.getTime() - lastAttemptTimestamp;
		if (timeSinceLastAttempt > 5 * 60 * 1000L) {
			// We haven't tried for at least 5 minutes

			if (networkBroadcastCount < 5) {
				// We've made less than 5 total attempts
				return true;
			}
		}

		if (timeSinceLastAttempt > 24 * 60 * 60 * 1000L) {
			// We haven't tried for at least 24 hours
			return true;
		}

		return false;
	}

	private boolean shouldMakeDirectFileRequestsForSignature(String signature58) {
		Triple<Integer, Integer, Long> request = arbitraryDataSignatureRequests.get(signature58);

		if (request == null) {
			// Not attempted yet
			return true;
		}

		// Extract the components
		//Integer networkBroadcastCount = request.getA();
		Integer directPeerRequestCount = request.getB();
		Long lastAttemptTimestamp = request.getC();

		if (lastAttemptTimestamp == null) {
			// Not attempted yet
			return true;
		}

		if (directPeerRequestCount == 0) {
			// We haven't tried asking peers directly yet, so we should
			return true;
		}

		long timeSinceLastAttempt = NTP.getTime() - lastAttemptTimestamp;
		if (timeSinceLastAttempt > 10 * 1000L) {
			// We haven't tried for at least 10 seconds
			if (directPeerRequestCount < 5) {
				// We've made less than 5 total attempts
				return true;
			}
		}

		if (timeSinceLastAttempt > 5 * 60 * 1000L) {
			// We haven't tried for at least 5 minutes
			if (directPeerRequestCount < 10) {
				// We've made less than 10 total attempts
				return true;
			}
		}

		if (timeSinceLastAttempt > 24 * 60 * 60 * 1000L) {
			// We haven't tried for at least 24 hours
			return true;
		}

		return false;
	}

	public boolean isSignatureRateLimited(byte[] signature) {
		String signature58 = Base58.encode(signature);
		return !this.shouldMakeFileListRequestForSignature(signature58)
				&& !this.shouldMakeDirectFileRequestsForSignature(signature58);
	}

	public long lastRequestForSignature(byte[] signature) {
		String signature58 = Base58.encode(signature);
		Triple<Integer, Integer, Long> request = arbitraryDataSignatureRequests.get(signature58);

		if (request == null) {
			// Not attempted yet
			return 0;
		}

		// Extract the components
		Long lastAttemptTimestamp = request.getC();
		if (lastAttemptTimestamp != null) {
			return  lastAttemptTimestamp;
		}
		return 0;
	}

	private void addToSignatureRequests(String signature58, boolean incrementNetworkRequests, boolean incrementPeerRequests) {
		Triple<Integer, Integer, Long> request  = arbitraryDataSignatureRequests.get(signature58);
		Long now = NTP.getTime();

		if (request == null) {
			// No entry yet
			Triple<Integer, Integer, Long> newRequest = new Triple<>(0, 0, now);
			arbitraryDataSignatureRequests.put(signature58, newRequest);
		}
		else {
			// There is an existing entry
			if (incrementNetworkRequests) {
				request.setA(request.getA() + 1);
			}
			if (incrementPeerRequests) {
				request.setB(request.getB() + 1);
			}
			request.setC(now);
			arbitraryDataSignatureRequests.put(signature58, request);
		}
	}

	private void removeFromSignatureRequests(String signature58) {
		arbitraryDataSignatureRequests.remove(signature58);
	}


	// Lookup file lists by signature

	public boolean fetchData(ArbitraryTransactionData arbitraryTransactionData) {
		return this.fetchArbitraryDataFileList(arbitraryTransactionData);
	}

	private boolean fetchArbitraryDataFileList(ArbitraryTransactionData arbitraryTransactionData) {
		byte[] signature = arbitraryTransactionData.getSignature();
		String signature58 = Base58.encode(signature);

		// If we've already tried too many times in a short space of time, make sure to give up
		if (!this.shouldMakeFileListRequestForSignature(signature58)) {
			// Check if we should make direct connections to peers
			if (this.shouldMakeDirectFileRequestsForSignature(signature58)) {
				return this.fetchDataFilesFromPeersForSignature(signature);
			}
			
			LOGGER.debug("Skipping file list request for signature {} due to rate limit", signature58);
			return false;
		}
		this.addToSignatureRequests(signature58, true, false);

		LOGGER.info(String.format("Sending data file list request for signature %s...", Base58.encode(signature)));

		// Build request
		Message getArbitraryDataFileListMessage = new GetArbitraryDataFileListMessage(signature);

		// Save our request into requests map
		Triple<String, Peer, Long> requestEntry = new Triple<>(signature58, null, NTP.getTime());

		// Assign random ID to this message
		int id;
		do {
			id = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;

			// Put queue into map (keyed by message ID) so we can poll for a response
			// If putIfAbsent() doesn't return null, then this ID is already taken
		} while (arbitraryDataFileListRequests.put(id, requestEntry) != null);
		getArbitraryDataFileListMessage.setId(id);

		// Broadcast request
		Network.getInstance().broadcast(peer -> getArbitraryDataFileListMessage);

		// Poll to see if data has arrived
		final long singleWait = 100;
		long totalWait = 0;
		while (totalWait < ARBITRARY_REQUEST_TIMEOUT) {
			try {
				Thread.sleep(singleWait);
			} catch (InterruptedException e) {
				break;
			}

			requestEntry = arbitraryDataFileListRequests.get(id);
			if (requestEntry == null)
				return false;

			if (requestEntry.getA() == null)
				break;

			totalWait += singleWait;
		}
		return true;
	}


	// Fetch data directly from peers

	private boolean fetchDataFilesFromPeersForSignature(byte[] signature) {
		String signature58 = Base58.encode(signature);
		this.addToSignatureRequests(signature58, false, true);

		// Firstly fetch peers that claim to be hosting files for this signature
		try (final Repository repository = RepositoryManager.getRepository()) {

			List<ArbitraryPeerData> peers = repository.getArbitraryRepository().getArbitraryPeerDataForSignature(signature);
			if (peers == null || peers.isEmpty()) {
				LOGGER.info("No peers found for signature {}", signature58);
				return false;
			}

			LOGGER.info("Attempting a direct peer connection for signature {}...", signature58);

			// Peers found, so pick a random one and request data from it
			int index = new SecureRandom().nextInt(peers.size());
			ArbitraryPeerData arbitraryPeerData = peers.get(index);
			String peerAddressString = arbitraryPeerData.getPeerAddress();
			return Network.getInstance().requestDataFromPeer(peerAddressString, signature);

		} catch (DataException e) {
			LOGGER.info("Unable to fetch peer list from repository");
		}

		return false;
	}


	// Fetch data files by hash

	private ArbitraryDataFile fetchArbitraryDataFile(Peer peer, byte[] signature, byte[] hash) {
		String hash58 = Base58.encode(hash);
		LOGGER.info(String.format("Fetching data file %.8s from peer %s", hash58, peer));
		arbitraryDataFileRequests.put(hash58, NTP.getTime());
		Message getArbitraryDataFileMessage = new GetArbitraryDataFileMessage(signature, hash);

		Message message = null;
		try {
			message = peer.getResponse(getArbitraryDataFileMessage);
		} catch (InterruptedException e) {
			// Will return below due to null message
		}
		arbitraryDataFileRequests.remove(hash58);
		LOGGER.info(String.format("Removed hash %.8s from arbitraryDataFileRequests", hash58));

		if (message == null || message.getType() != Message.MessageType.ARBITRARY_DATA_FILE) {
			return null;
		}

		ArbitraryDataFileMessage arbitraryDataFileMessage = (ArbitraryDataFileMessage) message;
		return arbitraryDataFileMessage.getArbitraryDataFile();
	}


	// Arbitrary data resource cache

	public void cleanupRequestCache(Long now) {
		if (now == null) {
			return;
		}
		final long requestMinimumTimestamp = now - ARBITRARY_REQUEST_TIMEOUT;
		arbitraryDataFileListRequests.entrySet().removeIf(entry -> entry.getValue().getC() == null || entry.getValue().getC() < requestMinimumTimestamp);
		arbitraryDataFileRequests.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < requestMinimumTimestamp);
	}

	public boolean isResourceCached(String resourceId) {
		if (resourceId == null) {
			return false;
		}
		resourceId = resourceId.toLowerCase();

		// We don't have an entry for this resource ID, it is not cached
		if (this.arbitraryDataCachedResources == null) {
			return false;
		}
		if (!this.arbitraryDataCachedResources.containsKey(resourceId)) {
			return false;
		}
		Long timestamp = this.arbitraryDataCachedResources.get(resourceId);
		if (timestamp == null) {
			return false;
		}

		// If the timestamp has reached the timeout, we should remove it from the cache
		long now = NTP.getTime();
		if (now > timestamp) {
			this.arbitraryDataCachedResources.remove(resourceId);
			return false;
		}

		// Current time hasn't reached the timeout, so treat it as cached
		return true;
	}

	public void addResourceToCache(String resourceId) {
		if (resourceId == null) {
			return;
		}
		resourceId = resourceId.toLowerCase();

		// Just in case
		if (this.arbitraryDataCachedResources == null) {
			this.arbitraryDataCachedResources = new HashMap<>();
		}

		Long now = NTP.getTime();
		if (now == null) {
			return;
		}

		// Set the timestamp to now + the timeout
		Long timestamp = NTP.getTime() + ARBITRARY_DATA_CACHE_TIMEOUT;
		this.arbitraryDataCachedResources.put(resourceId, timestamp);
	}

	public void invalidateCache(ArbitraryTransactionData arbitraryTransactionData) {
		String signature58 = Base58.encode(arbitraryTransactionData.getSignature());

		if (arbitraryTransactionData.getName() != null) {
			String resourceId = arbitraryTransactionData.getName().toLowerCase();
			LOGGER.info("We have all data for transaction {}", signature58);
			LOGGER.info("Clearing cache for name {}...", arbitraryTransactionData.getName());

			if (this.arbitraryDataCachedResources.containsKey(resourceId)) {
				this.arbitraryDataCachedResources.remove(resourceId);
			}

			// Also remove from the failed builds queue in case it previously failed due to missing chunks
			ArbitraryDataBuildManager buildManager = ArbitraryDataBuildManager.getInstance();
			if (buildManager.arbitraryDataFailedBuilds.containsKey(resourceId)) {
				buildManager.arbitraryDataFailedBuilds.remove(resourceId);
			}

			// Remove from the signature requests list now that we have all files for this signature
			this.removeFromSignatureRequests(signature58);
		}
	}

	public boolean fetchAllArbitraryDataFiles(Repository repository, Peer peer, byte[] signature) {
		try {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return false;

			ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

			// We use null to represent all hashes associated with this transaction
			return this.fetchArbitraryDataFiles(repository, peer, signature, arbitraryTransactionData, null);

		} catch (DataException e) {}

		return false;
	}

	public boolean fetchArbitraryDataFiles(Repository repository,
										Peer peer,
										byte[] signature,
										ArbitraryTransactionData arbitraryTransactionData,
										List<byte[]> hashes) throws DataException {

		// Load data file(s)
		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(arbitraryTransactionData.getData(), signature);
		byte[] metadataHash = arbitraryTransactionData.getMetadataHash();
		arbitraryDataFile.setMetadataHash(metadataHash);

		// If hashes are null, we will treat this to mean all data hashes associated with this file
		if (hashes == null) {
			if (metadataHash == null) {
				// This transaction has no metadata/chunks, so use the main file hash
				hashes = Arrays.asList(arbitraryDataFile.getHash());
			}
			else if (!arbitraryDataFile.getMetadataFile().exists()) {
				// We don't have the metadata file yet, so request it
				hashes = Arrays.asList(arbitraryDataFile.getMetadataFile().getHash());
			}
			else {
				// Add the chunk hashes
				hashes = arbitraryDataFile.getChunkHashes();
			}
		}

		boolean receivedAtLeastOneFile = false;

		// Now fetch actual data from this peer
		for (byte[] hash : hashes) {
			if (!arbitraryDataFile.chunkExists(hash)) {
				// Only request the file if we aren't already requesting it from someone else
				if (!arbitraryDataFileRequests.containsKey(Base58.encode(hash))) {
					ArbitraryDataFile receivedArbitraryDataFile = fetchArbitraryDataFile(peer, signature, hash);
					if (receivedArbitraryDataFile != null) {
						LOGGER.info("Received data file {} from peer {}", receivedArbitraryDataFile, peer);
						receivedAtLeastOneFile = true;
					}
					else {
						LOGGER.info("Peer {} didn't respond with data file {}", peer, Base58.encode(hash));
					}
				}
				else {
					LOGGER.info("Already requesting data file {}", arbitraryDataFile);
				}
			}
		}

		if (receivedAtLeastOneFile) {
			// Update our lookup table to indicate that this peer holds data for this signature
			String peerAddress = peer.getPeerData().getAddress().toString();
			LOGGER.info("Adding arbitrary peer: {} for signature {}", peerAddress, Base58.encode(signature));
			ArbitraryPeerData arbitraryPeerData = new ArbitraryPeerData(signature, peer);
			repository.discardChanges();
			repository.getArbitraryRepository().save(arbitraryPeerData);
			repository.saveChanges();
		}

		// Check if we have all the files we need for this transaction
		if (arbitraryDataFile.allFilesExist()) {

			// We have all the chunks for this transaction, so we should invalidate the transaction's name's
			// data cache so that it is rebuilt the next time we serve it
			invalidateCache(arbitraryTransactionData);

			// We may also need to broadcast to the network that we are now hosting files for this transaction,
			// but only if these files are in accordance with our storage policy
			if (ArbitraryDataStorageManager.getInstance().canStoreData(arbitraryTransactionData)) {
				// Use a null peer address to indicate our own
				Message newArbitrarySignatureMessage = new ArbitrarySignaturesMessage(null, Arrays.asList(signature));
				Network.getInstance().broadcast(broadcastPeer -> newArbitrarySignatureMessage);
			}
		}

		return receivedAtLeastOneFile;
	}


	// Network handlers

	public void onNetworkGetArbitraryDataMessage(Peer peer, Message message) {
		GetArbitraryDataMessage getArbitraryDataMessage = (GetArbitraryDataMessage) message;

		byte[] signature = getArbitraryDataMessage.getSignature();
		String signature58 = Base58.encode(signature);
		Long timestamp = NTP.getTime();
		Triple<String, Peer, Long> newEntry = new Triple<>(signature58, peer, timestamp);

		// If we've seen this request recently, then ignore
		if (arbitraryDataFileListRequests.putIfAbsent(message.getId(), newEntry) != null)
			return;

		// Do we even have this transaction?
		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (transactionData == null || transactionData.getType() != TransactionType.ARBITRARY)
				return;

			ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);

			// If we have the data then send it
			if (transaction.isDataLocal()) {
				byte[] data = transaction.fetchData();
				if (data == null)
					return;

				// Update requests map to reflect that we've sent it
				newEntry = new Triple<>(signature58, null, timestamp);
				arbitraryDataFileListRequests.put(message.getId(), newEntry);

				Message arbitraryDataMessage = new ArbitraryDataMessage(signature, data);
				arbitraryDataMessage.setId(message.getId());
				if (!peer.sendMessage(arbitraryDataMessage))
					peer.disconnect("failed to send arbitrary data");

				return;
			}

			// Ask our other peers if they have it
			Network.getInstance().broadcast(broadcastPeer -> broadcastPeer == peer ? null : message);
		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while finding arbitrary transaction data for peer %s", peer), e);
		}
	}

	public void onNetworkArbitraryDataFileListMessage(Peer peer, Message message) {
		ArbitraryDataFileListMessage arbitraryDataFileListMessage = (ArbitraryDataFileListMessage) message;
		LOGGER.info("Received hash list from peer {} with {} hashes", peer, arbitraryDataFileListMessage.getHashes().size());

		// Do we have a pending request for this data?
		Triple<String, Peer, Long> request = arbitraryDataFileListRequests.get(message.getId());
		if (request == null || request.getA() == null) {
			return;
		}

		// Does this message's signature match what we're expecting?
		byte[] signature = arbitraryDataFileListMessage.getSignature();
		String signature58 = Base58.encode(signature);
		if (!request.getA().equals(signature58)) {
			return;
		}

		List<byte[]> hashes = arbitraryDataFileListMessage.getHashes();
		if (hashes == null || hashes.isEmpty()) {
			return;
		}

		// Check transaction exists and hashes are correct
		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return;

			ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

			// Load data file(s)
			ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(arbitraryTransactionData.getData(), signature);
			arbitraryDataFile.setMetadataHash(arbitraryTransactionData.getMetadataHash());

//			// Check all hashes exist
//			for (byte[] hash : hashes) {
//				//LOGGER.info("Received hash {}", Base58.encode(hash));
//				if (!arbitraryDataFile.containsChunk(hash)) {
//					// Check the hash against the complete file
//					if (!Arrays.equals(arbitraryDataFile.getHash(), hash)) {
//						LOGGER.info("Received non-matching chunk hash {} for signature {}. This could happen if we haven't obtained the metadata file yet.", Base58.encode(hash), signature58);
//						return;
//					}
//				}
//			}

			// Update requests map to reflect that we've received it
			Triple<String, Peer, Long> newEntry = new Triple<>(null, null, request.getC());
			arbitraryDataFileListRequests.put(message.getId(), newEntry);

			// Go and fetch the actual data
			this.fetchArbitraryDataFiles(repository, peer, signature, arbitraryTransactionData, hashes);
			// FUTURE: handle response

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while finding arbitrary transaction data list for peer %s", peer), e);
		}

//		// Forwarding (not yet used)
//		Peer requestingPeer = request.getB();
//		if (requestingPeer != null) {
//			// Forward to requesting peer;
//			if (!requestingPeer.sendMessage(arbitraryDataFileListMessage)) {
//				requestingPeer.disconnect("failed to forward arbitrary data file list");
//			}
//		}
	}

	public void onNetworkGetArbitraryDataFileMessage(Peer peer, Message message) {
		GetArbitraryDataFileMessage getArbitraryDataFileMessage = (GetArbitraryDataFileMessage) message;
		byte[] hash = getArbitraryDataFileMessage.getHash();
		byte[] signature = getArbitraryDataFileMessage.getSignature();
		Controller.getInstance().stats.getArbitraryDataFileMessageStats.requests.incrementAndGet();

		try {
			ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, signature);

			if (arbitraryDataFile.exists()) {
				ArbitraryDataFileMessage arbitraryDataFileMessage = new ArbitraryDataFileMessage(signature, arbitraryDataFile);
				arbitraryDataFileMessage.setId(message.getId());
				if (!peer.sendMessage(arbitraryDataFileMessage)) {
					LOGGER.info("Couldn't sent file");
					peer.disconnect("failed to send file");
				}
				LOGGER.info("Sent file {}", arbitraryDataFile);
			}
			else {

				// We don't have this file
				Controller.getInstance().stats.getArbitraryDataFileMessageStats.unknownFiles.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer's synchronizer doesn't have to wait for timeout
				LOGGER.debug(String.format("Sending 'file unknown' response to peer %s for GET_FILE request for unknown file %s", peer, arbitraryDataFile));

				// We'll send empty block summaries message as it's very short
				// TODO: use a different message type here
				Message fileUnknownMessage = new BlockSummariesMessage(Collections.emptyList());
				fileUnknownMessage.setId(message.getId());
				if (!peer.sendMessage(fileUnknownMessage)) {
					LOGGER.info("Couldn't sent file-unknown response");
					peer.disconnect("failed to send file-unknown response");
				}
				LOGGER.info("Sent file-unknown response for file {}", arbitraryDataFile);
			}
		}
		catch (DataException e) {
			LOGGER.info("Unable to handle request for arbitrary data file: {}", Base58.encode(hash));
		}
	}

	public void onNetworkGetArbitraryDataFileListMessage(Peer peer, Message message) {
		GetArbitraryDataFileListMessage getArbitraryDataFileListMessage = (GetArbitraryDataFileListMessage) message;
		byte[] signature = getArbitraryDataFileListMessage.getSignature();
		Controller.getInstance().stats.getArbitraryDataFileListMessageStats.requests.incrementAndGet();

		LOGGER.info("Received hash list request from peer {} for signature {}", peer, Base58.encode(signature));

		List<byte[]> hashes = new ArrayList<>();

		try (final Repository repository = RepositoryManager.getRepository()) {

			// Firstly we need to lookup this file on chain to get a list of its hashes
			ArbitraryTransactionData transactionData = (ArbitraryTransactionData)repository.getTransactionRepository().fromSignature(signature);
			if (transactionData instanceof ArbitraryTransactionData) {

				// Check if we're even allowed to serve data for this transaction
				if (ArbitraryDataStorageManager.getInstance().canStoreData(transactionData)) {

					byte[] hash = transactionData.getData();
					byte[] metadataHash = transactionData.getMetadataHash();

					// Load file(s) and add any that exist to the list of hashes
					ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, signature);
					if (metadataHash != null) {
						arbitraryDataFile.setMetadataHash(metadataHash);

						// If we have the metadata file, add its hash
						if (arbitraryDataFile.getMetadataFile().exists()) {
							hashes.add(arbitraryDataFile.getMetadataHash());
						}

						for (ArbitraryDataFileChunk chunk : arbitraryDataFile.getChunks()) {
							if (chunk.exists()) {
								hashes.add(chunk.getHash());
								//LOGGER.info("Added hash {}", chunk.getHash58());
							} else {
								LOGGER.info("Couldn't add hash {} because it doesn't exist", chunk.getHash58());
							}
						}
					} else {
						// This transaction has no chunks, so include the complete file if we have it
						if (arbitraryDataFile.exists()) {
							hashes.add(arbitraryDataFile.getHash());
						}
					}
				}
			}

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while fetching arbitrary file list for peer %s", peer), e);
		}

		ArbitraryDataFileListMessage arbitraryDataFileListMessage = new ArbitraryDataFileListMessage(signature, hashes);
		arbitraryDataFileListMessage.setId(message.getId());
		if (!peer.sendMessage(arbitraryDataFileListMessage)) {
			LOGGER.info("Couldn't send list of hashes");
			peer.disconnect("failed to send list of hashes");
		}
		LOGGER.info("Sent list of hashes (count: {})", hashes.size());
	}

	public void onNetworkArbitrarySignaturesMessage(Peer peer, Message message) {
		LOGGER.info("Received arbitrary signature list from peer {}", peer);

		ArbitrarySignaturesMessage arbitrarySignaturesMessage = (ArbitrarySignaturesMessage) message;
		List<byte[]> signatures = arbitrarySignaturesMessage.getSignatures();

		String peerAddress = peer.getPeerData().getAddress().toString();
		if (arbitrarySignaturesMessage.getPeerAddress() != null) {
			// This message is about a different peer than the one that sent it
			peerAddress = arbitrarySignaturesMessage.getPeerAddress();
		}

		boolean containsNewEntry = false;

		// Synchronize peer data lookups to make this process thread safe. Otherwise we could broadcast
		// the same data multiple times, due to more than one thread processing the same message from different peers
		synchronized (this.peerDataLock) {
			try (final Repository repository = RepositoryManager.getRepository()) {
				for (byte[] signature : signatures) {

					// Check if a record already exists for this hash/peer combination
					ArbitraryPeerData existingEntry = repository.getArbitraryRepository()
							.getArbitraryPeerDataForSignatureAndPeer(signature, peer.getPeerData().getAddress().toString());

					if (existingEntry == null) {
						// We haven't got a record of this mapping yet, so add it
						LOGGER.info("Adding arbitrary peer: {} for signature {}", peerAddress, Base58.encode(signature));
						ArbitraryPeerData arbitraryPeerData = new ArbitraryPeerData(signature, peer);
						repository.getArbitraryRepository().save(arbitraryPeerData);
						repository.saveChanges();

						// Remember that this data is new, so that it can be re-broadcast later
						containsNewEntry = true;
					}
				}

				// If at least one signature in this batch was new to us, we should re-broadcast the message to the
				// network in case some peers haven't received it yet
				if (containsNewEntry) {
					LOGGER.info("Rebroadcasting arbitrary signature list for peer {}", peerAddress);
					Network.getInstance().broadcast(broadcastPeer -> arbitrarySignaturesMessage);
				} else {
					// Don't re-broadcast as otherwise we could get into a loop
				}

				// If anything needed saving, it would already have called saveChanges() above
				repository.discardChanges();
			} catch (DataException e) {
				LOGGER.error(String.format("Repository issue while processing arbitrary transaction signature list from peer %s", peer), e);
			}
		}
	}

}
