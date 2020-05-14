package org.qortal.crosschain;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBroadcast;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.listeners.BlocksDownloadedEventListener;
import org.bitcoinj.core.listeners.NewBestBlockListener;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script.ScriptType;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletTransaction;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener;
import org.qortal.settings.Settings;

public class BTC {

	public static final MonetaryFormat FORMAT = new MonetaryFormat().minDecimals(8).postfixCode();
	public static final long NO_LOCKTIME_NO_RBF_SEQUENCE = 0xFFFFFFFFL;
	public static final long LOCKTIME_NO_RBF_SEQUENCE = NO_LOCKTIME_NO_RBF_SEQUENCE - 1;
	public static final int HASH160_LENGTH = 20;

	private static final MessageDigest RIPE_MD160_DIGESTER;
	private static final MessageDigest SHA256_DIGESTER;
	static {
		try {
			RIPE_MD160_DIGESTER = MessageDigest.getInstance("RIPEMD160");
			SHA256_DIGESTER = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	protected static final Logger LOGGER = LogManager.getLogger(BTC.class);

	public enum BitcoinNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return MainNetParams.get();
			}
		},
		TEST3 {
			@Override
			public NetworkParameters getParams() {
				return TestNet3Params.get();
			}
		},
		REGTEST {
			@Override
			public NetworkParameters getParams() {
				return RegTestParams.get();
			}
		};

		public abstract NetworkParameters getParams();
	}

	private static class UpdateableCheckpointManager extends CheckpointManager implements NewBestBlockListener {
		private static final long CHECKPOINT_THRESHOLD = 7 * 24 * 60 * 60; // seconds

		private static final String MINIMAL_TESTNET3_TEXTFILE = "TXT CHECKPOINTS 1\n0\n1\nAAAAAAAAB+EH4QfhAAAH4AEAAAApmwX6UCEnJcYIKTa7HO3pFkqqNhAzJVBMdEuGAAAAAPSAvVCBUypCbBW/OqU0oIF7ISF84h2spOqHrFCWN9Zw6r6/T///AB0E5oOO\n";
		private static final String MINIMAL_MAINNET_TEXTFILE = "TXT CHECKPOINTS 1\n0\n1\nAAAAAAAAB+EH4QfhAAAH4AEAAABjl7tqvU/FIcDT9gcbVlA4nwtFUbxAtOawZzBpAAAAAKzkcK7NqciBjI/ldojNKncrWleVSgDfBCCn3VRrbSxXaw5/Sf//AB0z8Bkv\n";

		public UpdateableCheckpointManager(NetworkParameters params, File checkpointsFile) throws IOException, InterruptedException {
			super(params, getMinimalTextFileStream(params, checkpointsFile));
		}

		public UpdateableCheckpointManager(NetworkParameters params, InputStream inputStream) throws IOException {
			super(params, inputStream);
		}

		private static ByteArrayInputStream getMinimalTextFileStream(NetworkParameters params, File checkpointsFile) throws IOException, InterruptedException {
			if (params == MainNetParams.get())
				return new ByteArrayInputStream(MINIMAL_MAINNET_TEXTFILE.getBytes());

			if (params == TestNet3Params.get())
				return new ByteArrayInputStream(MINIMAL_TESTNET3_TEXTFILE.getBytes());

			if (params == RegTestParams.get())
				return newRegTestCheckpointsStream(checkpointsFile); // We have to build this

			throw new FileNotFoundException("Failed to construct empty UpdateableCheckpointManageer");
		}

		private static ByteArrayInputStream newRegTestCheckpointsStream(File checkpointsFile) throws IOException, InterruptedException {
			try {
				final NetworkParameters params = RegTestParams.get();

				final BlockStore store = new MemoryBlockStore(params);
				final BlockChain chain = new BlockChain(params, store);
				final PeerGroup peerGroup = new PeerGroup(params, chain);

				final InetAddress ipAddress = InetAddress.getLoopbackAddress();
				final PeerAddress peerAddress = new PeerAddress(params, ipAddress);
				peerGroup.addAddress(peerAddress);
				// startAsync().get() to allow interruption
				peerGroup.startAsync().get();

				final TreeMap<Integer, StoredBlock> checkpoints = new TreeMap<>();
				chain.addNewBestBlockListener((block) -> checkpoints.put(block.getHeight(), block));

				peerGroup.downloadBlockChain();
				peerGroup.stop();

				saveAsText(checkpointsFile, checkpoints.values());

				return new ByteArrayInputStream(Files.readAllBytes(checkpointsFile.toPath()));
			} catch (BlockStoreException e) {
				throw new IOException(e);
			} catch (ExecutionException e) {
				// Couldn't start peerGroup
				throw new IOException(e);
			}
		}

		@Override
		public void notifyNewBestBlock(StoredBlock block) {
			final int height = block.getHeight();

			if (height % this.params.getInterval() != 0)
				return;

			final long blockTimestamp = block.getHeader().getTimeSeconds();
			final long now = System.currentTimeMillis() / 1000L;
			if (blockTimestamp > now - CHECKPOINT_THRESHOLD)
				return; // Too recent

			LOGGER.trace(() -> String.format("Checkpointing at block %d dated %s", height, LocalDateTime.ofInstant(Instant.ofEpochSecond(blockTimestamp), ZoneOffset.UTC)));
			this.checkpoints.put(blockTimestamp, block);

			try {
				saveAsText(new File(BTC.getInstance().getDirectory(), BTC.getInstance().getCheckpointsFileName()), this.checkpoints.values());
			} catch (FileNotFoundException e) {
				// Save failed - log it but it's not critical
				LOGGER.warn(() -> String.format("Failed to save updated BTC checkpoints: %s", e.getMessage()));
			}
		}

		private static void saveAsText(File textFile, Collection<StoredBlock> checkpointBlocks) throws FileNotFoundException {
			try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(textFile), StandardCharsets.US_ASCII))) {
				writer.println("TXT CHECKPOINTS 1");
				writer.println("0"); // Number of signatures to read. Do this later.
				writer.println(checkpointBlocks.size());

				ByteBuffer buffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE);

				for (StoredBlock block : checkpointBlocks) {
					block.serializeCompact(buffer);
					writer.println(CheckpointManager.BASE64.encode(buffer.array()));
					buffer.position(0);
				}
			}
		}

		@SuppressWarnings("unused")
		public void saveAsBinary(File file) throws IOException {
			try (final FileOutputStream fileOutputStream = new FileOutputStream(file, false)) {
				MessageDigest digest = Sha256Hash.newDigest();

				try (final DigestOutputStream digestOutputStream = new DigestOutputStream(fileOutputStream, digest)) {
					digestOutputStream.on(false);

					try (final DataOutputStream dataOutputStream = new DataOutputStream(digestOutputStream)) {
						dataOutputStream.writeBytes("CHECKPOINTS 1");
						dataOutputStream.writeInt(0); // Number of signatures to read. Do this later.
						digestOutputStream.on(true);
						dataOutputStream.writeInt(this.checkpoints.size());

						ByteBuffer buffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE);

						for (StoredBlock block : this.checkpoints.values()) {
							block.serializeCompact(buffer);
							dataOutputStream.write(buffer.array());
							buffer.position(0);
						}
					}
				}
			}
		}
	}

	private static class ResettableBlockChain extends BlockChain {
		public ResettableBlockChain(NetworkParameters params, BlockStore blockStore) throws BlockStoreException {
			super(params, blockStore);
		}

		// Overridden to increase visibility to public
		@Override
		public void setChainHead(StoredBlock chainHead) throws BlockStoreException {
			super.setChainHead(chainHead);
		}
	}

	private static final Object instanceLock = new Object();
	private static BTC instance;
	private enum RunningState { RUNNING, STOPPED };
	FutureTask<RunningState> startupFuture;

	private final NetworkParameters params;
	private final String checkpointsFileName;
	private final File directory;

	private PeerGroup peerGroup;
	private BlockStore blockStore;
	private ResettableBlockChain chain;

	private UpdateableCheckpointManager manager;

	// Constructors and instance

	private BTC() {
		BitcoinNet bitcoinNet = Settings.getInstance().getBitcoinNet();
		this.params = bitcoinNet.getParams();

		switch (bitcoinNet) {
			case MAIN:
				this.checkpointsFileName = "checkpoints.txt";
				break;

			case TEST3:
				this.checkpointsFileName = "checkpoints-testnet.txt";
				break;

			case REGTEST:
				this.checkpointsFileName = "checkpoints-regtest.txt";
				break;

			default:
				throw new IllegalStateException("Unsupported Bitcoin network: " + bitcoinNet.name());
		}

		this.directory = new File("Qortal-BTC");

		startupFuture = new FutureTask<>(BTC::startUp);
	}

	public static BTC getInstance() {
		synchronized (instanceLock) {
			if (instance == null) {
				instance = new BTC();
				Executors.newSingleThreadExecutor().execute(instance.startupFuture);
			}

			return instance;
		}
	}

	// Getters & setters

	/* package */ File getDirectory() {
		return this.directory;
	}

	/* package */ String getCheckpointsFileName() {
		return this.checkpointsFileName;
	}

	public NetworkParameters getNetworkParameters() {
		return this.params;
	}

	// Static utility methods

	public static byte[] hash160(byte[] message) {
		return RIPE_MD160_DIGESTER.digest(SHA256_DIGESTER.digest(message));
	}

	// Start-up & shutdown

	private static RunningState startUp() {
		Thread.currentThread().setName("Bitcoin support");

		LOGGER.info(() -> String.format("Starting Bitcoin support using %s", Settings.getInstance().getBitcoinNet().name()));

		final long startTime = System.currentTimeMillis() / 1000L;

		if (!instance.directory.exists())
			if (!instance.directory.mkdirs()) {
				LOGGER.error(() -> String.format("Stopping Bitcoin support: couldn't create directory '%s'", instance.directory.getName()));
				return RunningState.STOPPED;
			}

		File checkpointsFile = new File(instance.directory, instance.checkpointsFileName);
		try (InputStream checkpointsStream = new FileInputStream(checkpointsFile)) {
			instance.manager = new UpdateableCheckpointManager(instance.params, checkpointsStream);
		} catch (FileNotFoundException e) {
			// Construct with no checkpoints then
			try {
				instance.manager = new UpdateableCheckpointManager(instance.params, checkpointsFile);
			} catch (IOException e2) {
				LOGGER.error(() -> String.format("Stopping Bitcoin support: couldn't create checkpoints file: %s", e.getMessage()));
				return RunningState.STOPPED;
			} catch (InterruptedException e2) {
				// Probably normal shutdown so quietly return
				LOGGER.debug("Stopping Bitcoin support due to interrupt");
				return RunningState.STOPPED;
			}
		} catch (IOException e) {
			LOGGER.error(() -> String.format("Stopping Bitcoin support: couldn't load checkpoints file: %s", e.getMessage()));
			return RunningState.STOPPED;
		}

		try {
			StoredBlock checkpoint = instance.manager.getCheckpointBefore(startTime - 1);

			instance.blockStore = new MemoryBlockStore(instance.params);
			instance.blockStore.put(checkpoint);
			instance.blockStore.setChainHead(checkpoint);

			instance.chain = new ResettableBlockChain(instance.params, instance.blockStore);
		} catch (BlockStoreException e) {
			LOGGER.error(() -> String.format("Stopping Bitcoin support: couldn't initialize blockstore: %s", e.getMessage()));
			return RunningState.STOPPED;
		}

		instance.peerGroup = new PeerGroup(instance.params, instance.chain);
		instance.peerGroup.setUserAgent("qortal", "1.0");
		// instance.peerGroup.setPingIntervalMsec(1000L);
		// instance.peerGroup.setMaxConnections(20);

		if (instance.params != RegTestParams.get()) {
			instance.peerGroup.addPeerDiscovery(new DnsDiscovery(instance.params));
		} else {
			instance.peerGroup.addAddress(PeerAddress.localhost(instance.params));
		}

		// final check that we haven't been interrupted
		if (Thread.currentThread().isInterrupted()) {
			LOGGER.debug("Stopping Bitcoin support due to interrupt");
			return RunningState.STOPPED;
		}

		// startAsync() so we can return
		instance.peerGroup.startAsync();

		return RunningState.RUNNING;
	}

	public static void shutdown() {
		// This is make sure we don't check instance
		// while some other thread is in the middle of BTC.getInstance()
		synchronized (instanceLock) {
			if (instance == null)
				return;
		}

		// If we can't cancel because we've finished start-up with RUNNING state then stop peerGroup.
		// Has side-effect of cancelling in-progress start-up, which is what we want too.
		try {
			if (!instance.startupFuture.cancel(true)
					&& instance.startupFuture.isDone()
					&& instance.startupFuture.get() == RunningState.RUNNING)
				instance.peerGroup.stop();
		} catch (InterruptedException | ExecutionException | CancellationException e) {
			// Start-up was in-progress when cancel() called, so this is ok
		}
	}

	public static void resetForTesting() {
		synchronized (instanceLock) {
			instance = null;
		}
	}

	// Utility methods

	/** Returns whether Bitcoin support is running, blocks until RUNNING if STARTING. */
	private boolean isRunning() {
		try {
			return this.startupFuture.get() == RunningState.RUNNING;
		} catch (InterruptedException | ExecutionException | CancellationException e) {
			return false;
		}
	}

	protected Wallet createEmptyWallet() {
		return Wallet.createBasic(this.params);
	}

	private class ReplayHooks {
		private Runnable preReplay;
		private Runnable postReplay;

		public ReplayHooks(Runnable preReplay, Runnable postReplay) {
			this.preReplay = preReplay;
			this.postReplay = postReplay;
		}

		public void preReplay() {
			this.preReplay.run();
		}

		public void postReplay() {
			this.postReplay.run();
		}
	}

	private void replayChain(int startTime, Wallet wallet, ReplayHooks replayHooks) throws BlockStoreException {
		StoredBlock checkpoint = this.manager.getCheckpointBefore(startTime - 1);
		this.blockStore.put(checkpoint);
		this.blockStore.setChainHead(checkpoint);
		this.chain.setChainHead(checkpoint);

		final WalletCoinsReceivedEventListener coinsReceivedListener = (someWallet, tx, prevBalance, newBalance) -> {
			LOGGER.trace(() -> String.format("Wallet-related transaction %s", tx.getTxId()));
		};

		final WalletCoinsSentEventListener coinsSentListener = (someWallet, tx, prevBalance, newBalance) -> {
			LOGGER.trace(() -> String.format("Wallet-related transaction %s", tx.getTxId()));
		};

		if (wallet != null) {
			wallet.addCoinsReceivedEventListener(coinsReceivedListener);
			wallet.addCoinsSentEventListener(coinsSentListener);

			// Link wallet to chain and peerGroup
			this.chain.addWallet(wallet);
			this.peerGroup.addWallet(wallet);
		}

		try {
			if (replayHooks != null)
				replayHooks.preReplay();

			// Sync blockchain using peerGroup, skipping as much as we can before startTime
			this.peerGroup.setFastCatchupTimeSecs(startTime);
			this.chain.addNewBestBlockListener(Threading.SAME_THREAD, this.manager);
			this.peerGroup.downloadBlockChain();
		} finally {
			// Clean up
			if (replayHooks != null)
				replayHooks.postReplay();

			if (wallet != null) {
				wallet.removeCoinsReceivedEventListener(coinsReceivedListener);
				wallet.removeCoinsSentEventListener(coinsSentListener);

				this.peerGroup.removeWallet(wallet);
				this.chain.removeWallet(wallet);
			}

			// For safety, disconnect download peer just in case
			Peer downloadPeer = this.peerGroup.getDownloadPeer();
			if (downloadPeer != null)
				downloadPeer.close();
		}
	}

	// Actual useful methods for use by other classes

	/** Returns median timestamp from latest 11 blocks, in seconds. */
	public Long getMedianBlockTime() {
		if (!this.isRunning())
			// Failed to start up, or we're shutting down
			return null;

		// 11 blocks, at roughly 10 minutes per block, means we should go back at least 110 minutes
		// but some blocks have been way longer than 10 minutes, so be massively pessimistic
		int startTime = (int) (System.currentTimeMillis() / 1000L) - 110 * 60; // 110 minutes before now, in seconds

		try {
			this.replayChain(startTime, null, null);

			List<StoredBlock> latestBlocks = new ArrayList<>(11);
			StoredBlock block = this.blockStore.getChainHead();
			for (int i = 0; i < 11; ++i) {
				latestBlocks.add(block);
				block = block.getPrev(this.blockStore);
			}

			// Descending, but order shouldn't matter as we're picking median...
			latestBlocks.sort((a, b) -> Long.compare(b.getHeader().getTimeSeconds(), a.getHeader().getTimeSeconds()));

			return latestBlocks.get(5).getHeader().getTimeSeconds();
		} catch (BlockStoreException e) {
			LOGGER.error(String.format("Can't get Bitcoin median block time due to blockstore issue: %s", e.getMessage()));
			return null;
		}
	}

	public Coin getBalance(String base58Address, int startTime) {
		if (!this.isRunning())
			// Failed to start up, or we're shutting down
			return null;

		// Create new wallet containing only the address we're interested in, ignoring anything prior to startTime
		Wallet wallet = createEmptyWallet();
		Address address = Address.fromString(this.params, base58Address);
		wallet.addWatchedAddress(address, startTime);

		try {
			replayChain(startTime, wallet, null);

			// Now that blockchain is up-to-date, return current balance
			return wallet.getBalance();
		} catch (BlockStoreException e) {
			LOGGER.error(String.format("Can't get Bitcoin balance for %s due to blockstore issue: %s", base58Address, e.getMessage()));
			return null;
		}
	}

	public List<TransactionOutput> getOutputs(String base58Address, int startTime) {
		if (!this.isRunning())
			// Failed to start up, or we're shutting down
			return null;

		Wallet wallet = createEmptyWallet();
		Address address = Address.fromString(this.params, base58Address);
		wallet.addWatchedAddress(address, startTime);

		try {
			replayChain(startTime, wallet, null);

			// Now that blockchain is up-to-date, return outputs
			return wallet.getWatchedOutputs(true);
		} catch (BlockStoreException e) {
			LOGGER.error(String.format("Can't get Bitcoin outputs for %s due to blockstore issue: %s", base58Address, e.getMessage()));
			return null;
		}
	}

	public Coin getBalanceAndOtherInfo(String base58Address, int startTime, List<TransactionOutput> unspentOutputs, List<WalletTransaction> walletTransactions) {
		if (!this.isRunning())
			// Failed to start up, or we're shutting down
			return null;

		// Create new wallet containing only the address we're interested in, ignoring anything prior to startTime
		Wallet wallet = createEmptyWallet();
		Address address = Address.fromString(this.params, base58Address);
		wallet.addWatchedAddress(address, startTime);

		try {
			replayChain(startTime, wallet, null);

			if (unspentOutputs != null)
				unspentOutputs.addAll(wallet.getWatchedOutputs(true));

			if (walletTransactions != null)
				for (WalletTransaction walletTransaction : wallet.getWalletTransactions())
					walletTransactions.add(walletTransaction);

			return wallet.getBalance();
		} catch (BlockStoreException e) {
			LOGGER.error(String.format("Can't get Bitcoin info for %s due to blockstore issue: %s", base58Address, e.getMessage()));
			return null;
		}
	}

	public List<TransactionOutput> getOutputs(byte[] txId, int startTime) {
		if (!this.isRunning())
			// Failed to start up, or we're shutting down
			return null;

		Wallet wallet = createEmptyWallet();

		// Add random address to wallet
		ECKey fakeKey = new ECKey();
		wallet.addWatchedAddress(Address.fromKey(this.params, fakeKey, ScriptType.P2PKH), startTime);

		final Sha256Hash txHash = Sha256Hash.wrap(txId);

		final AtomicReference<Transaction> foundTransaction = new AtomicReference<>();

		final BlocksDownloadedEventListener listener = (peer, block, filteredBlock, blocksLeft) -> {
			List<Transaction> transactions = block.getTransactions();

			if (transactions == null)
				return;

			for (Transaction transaction : transactions)
				if (transaction.getTxId().equals(txHash)) {
					LOGGER.trace(() -> String.format("We downloaded block containing tx %s", txHash));
					foundTransaction.set(transaction);
				}
		};

		ReplayHooks replayHooks = new ReplayHooks(() -> this.peerGroup.addBlocksDownloadedEventListener(listener), () -> this.peerGroup.removeBlocksDownloadedEventListener(listener));

		// Replay chain in the hope it will download transactionId as a dependency
		try {
			replayChain(startTime, wallet, replayHooks);

			Transaction realTx = foundTransaction.get();
			return realTx.getOutputs();
		} catch (BlockStoreException e) {
			LOGGER.error(String.format("BTC blockstore issue: %s", e.getMessage()));
			return null;
		}
	}

	public boolean broadcastTransaction(Transaction transaction) {
		if (!this.isRunning())
			// Failed to start up, or we're shutting down
			return false;

		TransactionBroadcast transactionBroadcast = this.peerGroup.broadcastTransaction(transaction);

		try {
			transactionBroadcast.future().get();
			return true;
		} catch (InterruptedException | ExecutionException e) {
			return false;
		}
	}

}
