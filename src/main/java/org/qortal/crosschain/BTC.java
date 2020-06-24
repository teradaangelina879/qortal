package org.qortal.crosschain;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.utils.MonetaryFormat;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.utils.BitTwiddling;
import org.qortal.utils.Pair;

public class BTC {

	public static final long NO_LOCKTIME_NO_RBF_SEQUENCE = 0xFFFFFFFFL;
	public static final long LOCKTIME_NO_RBF_SEQUENCE = NO_LOCKTIME_NO_RBF_SEQUENCE - 1;
	public static final int HASH160_LENGTH = 20;

	protected static final Logger LOGGER = LogManager.getLogger(BTC.class);

	private static final int TIMESTAMP_OFFSET = 4 + 32 + 32;
	private static final MonetaryFormat FORMAT = new MonetaryFormat().minDecimals(8).postfixCode();

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

	private static BTC instance;
	private final NetworkParameters params;
	private final ElectrumX electrumX;

	// Constructors and instance

	private BTC() {
		BitcoinNet bitcoinNet = Settings.getInstance().getBitcoinNet();
		this.params = bitcoinNet.getParams();

		LOGGER.info(() -> String.format("Starting Bitcoin support using %s", bitcoinNet.name()));

		this.electrumX = ElectrumX.getInstance(bitcoinNet.name());
	}

	public static synchronized BTC getInstance() {
		if (instance == null)
			instance = new BTC();

		return instance;
	}

	// Getters & setters

	public NetworkParameters getNetworkParameters() {
		return this.params;
	}

	public static synchronized void resetForTesting() {
		instance = null;
	}

	// Actual useful methods for use by other classes

	public static String format(Coin amount) {
		return BTC.FORMAT.format(amount).toString();
	}

	public static String format(long amount) {
		return format(Coin.valueOf(amount));
	}

	public String pkhToAddress(byte[] publicKeyHash) {
		return LegacyAddress.fromPubKeyHash(this.params, publicKeyHash).toString();
	}

	public String deriveP2shAddress(byte[] redeemScriptBytes) {
		byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
		Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
		return p2shAddress.toString();
	}

	/** Returns median timestamp from latest 11 blocks, in seconds. */
	public Integer getMedianBlockTime() {
		Integer height = this.electrumX.getCurrentHeight();
		if (height == null)
			return null;

		// Grab latest 11 blocks
		List<byte[]> blockHeaders = this.electrumX.getBlockHeaders(height - 11, 11);
		if (blockHeaders == null || blockHeaders.size() < 11)
			return null;

		List<Integer> blockTimestamps = blockHeaders.stream().map(blockHeader -> BitTwiddling.intFromLEBytes(blockHeader, TIMESTAMP_OFFSET)).collect(Collectors.toList());

		// Descending, but order shouldn't matter as we're picking median...
		blockTimestamps.sort((a, b) -> Integer.compare(b, a));

		return blockTimestamps.get(5);
	}

	public Long getBalance(String base58Address) {
		return this.electrumX.getBalance(addressToScript(base58Address));
	}

	public List<TransactionOutput> getUnspentOutputs(String base58Address) {
		List<Pair<byte[], Integer>> unspentOutputs = this.electrumX.getUnspentOutputs(addressToScript(base58Address));
		if (unspentOutputs == null)
			return null;

		List<TransactionOutput> unspentTransactionOutputs = new ArrayList<>();
		for (Pair<byte[], Integer> unspentOutput : unspentOutputs) {
			List<TransactionOutput> transactionOutputs = getOutputs(unspentOutput.getA());
			if (transactionOutputs == null)
				return null;

			unspentTransactionOutputs.add(transactionOutputs.get(unspentOutput.getB()));
		}

		return unspentTransactionOutputs;
	}

	public List<TransactionOutput> getOutputs(byte[] txHash) {
		byte[] rawTransactionBytes = this.electrumX.getRawTransaction(txHash);
		if (rawTransactionBytes == null)
			return null;

		Transaction transaction = new Transaction(this.params, rawTransactionBytes);
		return transaction.getOutputs();
	}

	public List<byte[]> getAddressTransactions(String base58Address) {
		return this.electrumX.getAddressTransactions(addressToScript(base58Address));
	}

	public boolean broadcastTransaction(Transaction transaction) {
		return this.electrumX.broadcastTransaction(transaction.bitcoinSerialize());
	}

	// Utility methods for us

	private byte[] addressToScript(String base58Address) {
		Address address = Address.fromString(this.params, base58Address);
		return ScriptBuilder.createOutputScript(address).getProgram();
	}

}
