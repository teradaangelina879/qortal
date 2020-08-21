package org.qortal.crosschain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.core.UTXOProvider;
import org.bitcoinj.core.UTXOProviderException;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script.ScriptType;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.qortal.crosschain.ElectrumX.UnspentOutput;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.utils.BitTwiddling;

import com.google.common.hash.HashCode;

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

	// Let ECKey.equals() do the hard work
	private final Set<ECKey> spentKeys = new HashSet<>();

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

	public boolean isValidXprv(String xprv58) {
		try {
			DeterministicKey.deserializeB58(null, xprv58, this.params);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** Returns P2PKH Bitcoin address using passed public key hash. */
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

		// Descending order
		blockTimestamps.sort((a, b) -> Integer.compare(b, a));

		// Pick median
		return blockTimestamps.get(5);
	}

	public Long getBalance(String base58Address) {
		return this.electrumX.getBalance(addressToScript(base58Address));
	}

	public List<TransactionOutput> getUnspentOutputs(String base58Address) {
		List<UnspentOutput> unspentOutputs = this.electrumX.getUnspentOutputs(addressToScript(base58Address));
		if (unspentOutputs == null)
			return null;

		List<TransactionOutput> unspentTransactionOutputs = new ArrayList<>();
		for (UnspentOutput unspentOutput : unspentOutputs) {
			List<TransactionOutput> transactionOutputs = getOutputs(unspentOutput.hash);
			if (transactionOutputs == null)
				return null;

			unspentTransactionOutputs.add(transactionOutputs.get(unspentOutput.index));
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

	/** Returns list of raw transactions spending passed address. */
	public List<byte[]> getAddressTransactions(String base58Address) {
		return this.electrumX.getAddressTransactions(addressToScript(base58Address));
	}

	public boolean broadcastTransaction(Transaction transaction) {
		return this.electrumX.broadcastTransaction(transaction.bitcoinSerialize());
	}

	/**
	 * Returns bitcoinj transaction sending <tt>amount</tt> to <tt>recipient</tt>.
	 * 
	 * @param xprv58 BIP32 extended Bitcoin private key
	 * @param recipient P2PKH address
	 * @param amount unscaled amount
	 * @return transaction, or null if insufficient funds
	 */
	public Transaction buildSpend(String xprv58, String recipient, long amount) {
		Wallet wallet = Wallet.fromSpendingKeyB58(this.params, xprv58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		wallet.setUTXOProvider(new WalletAwareUTXOProvider(this, wallet, WalletAwareUTXOProvider.KeySearchMode.REQUEST_MORE_IF_ALL_SPENT));

		Address destination = Address.fromString(this.params, recipient);
		SendRequest sendRequest = SendRequest.to(destination, Coin.valueOf(amount));

		if (this.params == TestNet3Params.get())
			// Much smaller fee for TestNet3
			sendRequest.feePerKb = Coin.valueOf(2000L);

		try {
			wallet.completeTx(sendRequest);
			return sendRequest.tx;
		} catch (InsufficientMoneyException e) {
			return null;
		}
	}

	/**
	 * Returns unspent Bitcoin balance given 'm' BIP32 key.
	 *
	 * @param xprv58 BIP32 extended Bitcoin private key
	 * @return unspent BTC balance, or null if unable to determine balance
	 */
	public Long getWalletBalance(String xprv58) {
		Wallet wallet = Wallet.fromSpendingKeyB58(this.params, xprv58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		wallet.setUTXOProvider(new WalletAwareUTXOProvider(this, wallet, WalletAwareUTXOProvider.KeySearchMode.REQUEST_MORE_IF_ANY_SPENT));

		Coin balance = wallet.getBalance();
		if (balance == null)
			return null;

		return balance.value;
	}

	/**
	 * Returns first unused receive address given 'm' BIP32 key.
	 *
	 * @param xprv58 BIP32 extended Bitcoin private key
	 * @return Bitcoin P2PKH address, or null if something went wrong
	 */
	public String getUnusedReceiveAddress(String xprv58) {
		Wallet wallet = Wallet.fromSpendingKeyB58(this.params, xprv58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		DeterministicKeyChain keyChain = wallet.getActiveKeyChain();

		keyChain.setLookaheadSize(WalletAwareUTXOProvider.LOOKAHEAD_INCREMENT);
		keyChain.maybeLookAhead();

		final int keyChainPathSize = keyChain.getAccountPath().size();
		List<DeterministicKey> keys = new ArrayList<>(keyChain.getLeafKeys());

		int ki = 0;
		do {
			for (; ki < keys.size(); ++ki) {
				DeterministicKey dKey = keys.get(ki);
				List<ChildNumber> dKeyPath = dKey.getPath();

				// If keyChain is based on 'm', then make sure dKey is m/0/ki
				if (dKeyPath.size() != keyChainPathSize + 2 || dKeyPath.get(dKeyPath.size() - 2) != ChildNumber.ZERO)
					continue;

				// Check unspent
				Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
				byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

				List<UnspentOutput> unspentOutputs = this.electrumX.getUnspentOutputs(script);
				if (unspentOutputs == null)
					return null;

				/*
				 * If there are no unspent outputs then either:
				 * a) all the outputs have been spent
				 * b) address has never been used
				 * 
				 * For case (a) we want to remember not to check this address (key) again.
				 */

				if (unspentOutputs.isEmpty()) {
					// If this is a known key that has been spent before, then we can skip asking for transaction history
					if (this.spentKeys.contains(dKey)) {
						wallet.getActiveKeyChain().markKeyAsUsed((DeterministicKey) dKey);
						continue;
					}

					// Ask for transaction history - if it's empty then key has never been used
					List<byte[]> historicTransactionHashes = this.electrumX.getAddressTransactions(script);
					if (historicTransactionHashes == null)
						return null;

					if (!historicTransactionHashes.isEmpty()) {
						// Fully spent key - case (a)
						this.spentKeys.add(dKey);
						wallet.getActiveKeyChain().markKeyAsUsed(dKey);
					} else {
						// Key never been used - case (b)
						return address.toString();
					}
				}

				// Key has unspent outputs, hence used, so no good to us
				this.spentKeys.remove(dKey);
			}

			// Generate some more keys
			keyChain.setLookaheadSize(keyChain.getLookaheadSize() + WalletAwareUTXOProvider.LOOKAHEAD_INCREMENT);
			keyChain.maybeLookAhead();

			// This returns all keys, including those already in 'keys'
			List<DeterministicKey> allLeafKeys = keyChain.getLeafKeys();
			// Add only new keys onto our list of keys to search
			List<DeterministicKey> newKeys = allLeafKeys.subList(ki, allLeafKeys.size());
			keys.addAll(newKeys);
			// Fall-through to checking more keys as now 'ki' is smaller than 'keys.size()' again

			// Process new keys
		} while (true);
	}

	// UTXOProvider support

	static class WalletAwareUTXOProvider implements UTXOProvider {
		private static final int LOOKAHEAD_INCREMENT = 3;

		private final BTC btc;
		private final Wallet wallet;

		enum KeySearchMode {
			REQUEST_MORE_IF_ALL_SPENT, REQUEST_MORE_IF_ANY_SPENT;
		}
		private final KeySearchMode keySearchMode;
		private final DeterministicKeyChain keyChain;

		public WalletAwareUTXOProvider(BTC btc, Wallet wallet, KeySearchMode keySearchMode) {
			this.btc = btc;
			this.wallet = wallet;
			this.keySearchMode = keySearchMode;
			this.keyChain = this.wallet.getActiveKeyChain();

			// Set up wallet's key chain
			this.keyChain.setLookaheadSize(LOOKAHEAD_INCREMENT);
			this.keyChain.maybeLookAhead();
		}

		public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws UTXOProviderException {
			List<UTXO> allUnspentOutputs = new ArrayList<>();
			final boolean coinbase = false;

			int ki = 0;
			do {
				boolean areAllKeysUnspent = true;
				boolean areAllKeysSpent = true;

				for (; ki < keys.size(); ++ki) {
					ECKey key = keys.get(ki);

					Address address = Address.fromKey(btc.params, key, ScriptType.P2PKH);
					byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

					List<UnspentOutput> unspentOutputs = btc.electrumX.getUnspentOutputs(script);
					if (unspentOutputs == null)
						throw new UTXOProviderException(String.format("Unable to fetch unspent outputs for %s", address));

					/*
					 * If there are no unspent outputs then either:
					 * a) all the outputs have been spent
					 * b) address has never been used
					 * 
					 * For case (a) we want to remember not to check this address (key) again.
					 */

					if (unspentOutputs.isEmpty()) {
						// If this is a known key that has been spent before, then we can skip asking for transaction history
						if (btc.spentKeys.contains(key)) {
							wallet.getActiveKeyChain().markKeyAsUsed((DeterministicKey) key);
							areAllKeysUnspent = false;
							continue;
						}

						// Ask for transaction history - if it's empty then key has never been used
						List<byte[]> historicTransactionHashes = btc.electrumX.getAddressTransactions(script);
						if (historicTransactionHashes == null)
							throw new UTXOProviderException(
									String.format("Unable to fetch transaction history for %s", address));

						if (!historicTransactionHashes.isEmpty()) {
							// Fully spent key - case (a)
							btc.spentKeys.add(key);
							wallet.getActiveKeyChain().markKeyAsUsed((DeterministicKey) key);
							areAllKeysUnspent = false;
						} else {
							// Key never been used - case (b)
							areAllKeysSpent = false;
						}

						continue;
					}

					// If we reach here, then there's definitely at least one unspent key
					btc.spentKeys.remove(key);
					areAllKeysSpent = false;

					for (UnspentOutput unspentOutput : unspentOutputs) {
						List<TransactionOutput> transactionOutputs = btc.getOutputs(unspentOutput.hash);
						if (transactionOutputs == null)
							throw new UTXOProviderException(String.format("Unable to fetch outputs for TX %s",
									HashCode.fromBytes(unspentOutput.hash)));

						TransactionOutput transactionOutput = transactionOutputs.get(unspentOutput.index);

						UTXO utxo = new UTXO(Sha256Hash.wrap(unspentOutput.hash), unspentOutput.index,
								Coin.valueOf(unspentOutput.value), unspentOutput.height, coinbase,
								transactionOutput.getScriptPubKey());

						allUnspentOutputs.add(utxo);
					}
				}

				if ((this.keySearchMode == KeySearchMode.REQUEST_MORE_IF_ALL_SPENT && areAllKeysSpent)
						|| (this.keySearchMode == KeySearchMode.REQUEST_MORE_IF_ANY_SPENT && !areAllKeysUnspent)) {
					// Generate some more keys
					this.keyChain.setLookaheadSize(this.keyChain.getLookaheadSize() + LOOKAHEAD_INCREMENT);
					this.keyChain.maybeLookAhead();

					// This returns all keys, including those already in 'keys'
					List<DeterministicKey> allLeafKeys = this.keyChain.getLeafKeys();
					// Add only new keys onto our list of keys to search
					List<DeterministicKey> newKeys = allLeafKeys.subList(ki, allLeafKeys.size());
					keys.addAll(newKeys);
					// Fall-through to checking more keys as now 'ki' is smaller than 'keys.size()' again
				}

				// If we have processed all keys, then we're done
			} while (ki < keys.size());

			return allUnspentOutputs;
		}

		public int getChainHeadHeight() throws UTXOProviderException {
			Integer height = btc.electrumX.getCurrentHeight();
			if (height == null)
				throw new UTXOProviderException("Unable to determine Bitcoin chain height");

			return height.intValue();
		}

		public NetworkParameters getParams() {
			return btc.params;
		}
	}

	// Utility methods for us

	private byte[] addressToScript(String base58Address) {
		Address address = Address.fromString(this.params, base58Address);
		return ScriptBuilder.createOutputScript(address).getProgram();
	}

}
