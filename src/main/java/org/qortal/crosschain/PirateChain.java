package org.qortal.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.libdohj.params.LitecoinMainNetParams;
import org.libdohj.params.LitecoinRegTestParams;
import org.libdohj.params.LitecoinTestNet3Params;
import org.qortal.crosschain.PirateLightClient.Server;
import org.qortal.crosschain.PirateLightClient.Server.ConnectionType;
import org.qortal.settings.Settings;

import java.util.*;

public class PirateChain extends Bitcoiny {

	public static final String CURRENCY_CODE = "ARRR";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(10000); // 0.0001 ARRR per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 50000000; // 0.5 ARRR minimum order, to avoid dust errors // TODO: may need calibration

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 10000L; // 0.0001 ARRR
	private static final long NON_MAINNET_FEE = 10000L; // 0.0001 ARRR

	private static final Map<ConnectionType, Integer> DEFAULT_LITEWALLET_PORTS = new EnumMap<>(ConnectionType.class);
	static {
		DEFAULT_LITEWALLET_PORTS.put(ConnectionType.TCP, 9067);
		DEFAULT_LITEWALLET_PORTS.put(ConnectionType.SSL, 443);
	}

	public enum PirateChainNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return LitecoinMainNetParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
						// Servers chosen on NO BASIS WHATSOEVER from various sources!
						new Server("lightd.pirate.black", ConnectionType.SSL, 443));
			}

			@Override
			public String getGenesisHash() {
				return "027e3758c3a65b12aa1046462b486d0a63bfa1beae327897f56c5cfb7daaae71";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				// TODO: This will need to be replaced with something better in the near future!
				return MAINNET_FEE;
			}
		},
		TEST3 {
			@Override
			public NetworkParameters getParams() {
				return LitecoinTestNet3Params.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList();
			}

			@Override
			public String getGenesisHash() {
				return "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return NON_MAINNET_FEE;
			}
		},
		REGTEST {
			@Override
			public NetworkParameters getParams() {
				return LitecoinRegTestParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
						new Server("localhost", ConnectionType.TCP, 9067),
						new Server("localhost", ConnectionType.SSL, 443));
			}

			@Override
			public String getGenesisHash() {
				// This is unique to each regtest instance
				return null;
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return NON_MAINNET_FEE;
			}
		};

		public abstract NetworkParameters getParams();
		public abstract Collection<Server> getServers();
		public abstract String getGenesisHash();
		public abstract long getP2shFee(Long timestamp) throws ForeignBlockchainException;
	}

	private static PirateChain instance;

	private final PirateChainNet pirateChainNet;

	// Constructors and instance

	private PirateChain(PirateChainNet pirateChainNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		super(blockchain, bitcoinjContext, currencyCode);
		this.pirateChainNet = pirateChainNet;

		LOGGER.info(() -> String.format("Starting Pirate Chain support using %s", this.pirateChainNet.name()));
	}

	public static synchronized PirateChain getInstance() {
		if (instance == null) {
			PirateChainNet pirateChainNet = Settings.getInstance().getPirateChainNet();

			BitcoinyBlockchainProvider pirateLightClient = new PirateLightClient("PirateChain-" + pirateChainNet.name(), pirateChainNet.getGenesisHash(), pirateChainNet.getServers(), DEFAULT_LITEWALLET_PORTS);
			Context bitcoinjContext = new Context(pirateChainNet.getParams());

			instance = new PirateChain(pirateChainNet, pirateLightClient, bitcoinjContext, CURRENCY_CODE);

			pirateLightClient.setBlockchain(instance);
		}

		return instance;
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		instance = null;
	}

	// Actual useful methods for use by other classes

	/** Default Litecoin fee is lower than Bitcoin: only 10sats/byte. */
	@Override
	public Coin getFeePerKb() {
		return DEFAULT_FEE_PER_KB;
	}

	@Override
	public long getMinimumOrderAmount() {
		return MINIMUM_ORDER_AMOUNT;
	}

	/**
	 * Returns estimated LTC fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.pirateChainNet.getP2shFee(timestamp);
	}

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if balance unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	public long getConfirmedBalance(String base58Address) throws ForeignBlockchainException {
		return this.blockchainProvider.getConfirmedAddressBalance(base58Address);
	}

	/**
	 * Returns median timestamp from latest 11 blocks, in seconds.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public int getMedianBlockTime() throws ForeignBlockchainException {
		int height = this.blockchainProvider.getCurrentHeight();

		// Grab latest 11 blocks
		List<Long> blockTimestamps = this.blockchainProvider.getBlockTimestamps(height - 11, 11);
		if (blockTimestamps.size() < 11)
			throw new ForeignBlockchainException("Not enough blocks to determine median block time");

		// Descending order
		blockTimestamps.sort((a, b) -> Long.compare(b, a));

		// Pick median
		return Math.toIntExact(blockTimestamps.get(5));
	}

	/**
	 * Returns list of compact blocks
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public List<CompactFormats.CompactBlock> getCompactBlocks(int startHeight, int count) throws ForeignBlockchainException {
		return this.blockchainProvider.getCompactBlocks(startHeight, count);
	}

}
