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
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.utils.BitTwiddling;

import com.google.common.hash.HashCode;

public class BTC {

	public static final long NO_LOCKTIME_NO_RBF_SEQUENCE = 0xFFFFFFFFL;
	public static final long LOCKTIME_NO_RBF_SEQUENCE = NO_LOCKTIME_NO_RBF_SEQUENCE - 1;
	public static final int HASH160_LENGTH = 20;

	public static final boolean INCLUDE_UNCONFIRMED = true;
	public static final boolean EXCLUDE_UNCONFIRMED = false;

	protected static final Logger LOGGER = LogManager.getLogger(BTC.class);

	// Temporary values until a dynamic fee system is written.
	private static final long OLD_FEE_AMOUNT = 4_000L; // Not 5000 so that existing P2SH-B can output 1000, avoiding dust issue, leaving 4000 for fees.
	private static final long NEW_FEE_TIMESTAMP = 1598280000000L; // milliseconds since epoch
	private static final long NEW_FEE_AMOUNT = 10_000L;
	private static final long NON_MAINNET_FEE = 1000L; // enough for TESTNET3 and should be OK for REGTEST

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

	/**
	 * Returns median timestamp from latest 11 blocks, in seconds.
	 * <p>
	 * @throws BitcoinException if error occurs
	 */
	public Integer getMedianBlockTime() throws BitcoinException {
		int height = this.electrumX.getCurrentHeight();

		// Grab latest 11 blocks
		List<byte[]> blockHeaders = this.electrumX.getBlockHeaders(height - 11, 11);
		if (blockHeaders.size() < 11)
			throw new BitcoinException("Not enough blocks to determine median block time");

		List<Integer> blockTimestamps = blockHeaders.stream().map(blockHeader -> BitTwiddling.intFromLEBytes(blockHeader, TIMESTAMP_OFFSET)).collect(Collectors.toList());

		// Descending order
		blockTimestamps.sort((a, b) -> Integer.compare(b, a));

		// Pick median
		return blockTimestamps.get(5);
	}

	/**
	 * Returns estimated BTC fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws BitcoinException if something went wrong
	 */
	public long estimateFee(Long timestamp) throws BitcoinException {
		if (!this.params.getId().equals(NetworkParameters.ID_MAINNET))
			return NON_MAINNET_FEE;

		// TODO: This will need to be replaced with something better in the near future!
		if (timestamp != null && timestamp < NEW_FEE_TIMESTAMP)
			return OLD_FEE_AMOUNT;

		return NEW_FEE_AMOUNT;
	}

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if script unknown
	 * @throws BitcoinException if there was an error
	 */
	public long getConfirmedBalance(String base58Address) throws BitcoinException {
		return this.electrumX.getConfirmedBalance(addressToScript(base58Address));
	}

	/**
	 * Returns list of unspent outputs pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if address unknown
	 * @throws BitcoinException if there was an error.
	 */
	public List<TransactionOutput> getUnspentOutputs(String base58Address) throws BitcoinException {
		List<UnspentOutput> unspentOutputs = this.electrumX.getUnspentOutputs(addressToScript(base58Address), false);

		List<TransactionOutput> unspentTransactionOutputs = new ArrayList<>();
		for (UnspentOutput unspentOutput : unspentOutputs) {
			List<TransactionOutput> transactionOutputs = this.getOutputs(unspentOutput.hash);

			unspentTransactionOutputs.add(transactionOutputs.get(unspentOutput.index));
		}

		return unspentTransactionOutputs;
	}

	/**
	 * Returns list of outputs pertaining to passed transaction hash.
	 * <p>
	 * @return list of outputs, or empty list if transaction unknown
	 * @throws BitcoinException if there was an error.
	 */
	public List<TransactionOutput> getOutputs(byte[] txHash) throws BitcoinException {
		byte[] rawTransactionBytes = this.electrumX.getRawTransaction(txHash);

		// XXX bitcoinj: replace with getTransaction() below
		Transaction transaction = new Transaction(this.params, rawTransactionBytes);
		return transaction.getOutputs();
	}

	/**
	 * Returns list of transaction hashes pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if script unknown
	 * @throws BitcoinException if there was an error.
	 */
	public List<TransactionHash> getAddressTransactions(String base58Address, boolean includeUnconfirmed) throws BitcoinException {
		return this.electrumX.getAddressTransactions(addressToScript(base58Address), includeUnconfirmed);
	}

	/**
	 * Returns list of raw, confirmed transactions involving given address.
	 * <p>
	 * @throws BitcoinException if there was an error
	 */
	public List<byte[]> getAddressTransactions(String base58Address) throws BitcoinException {
		List<TransactionHash> transactionHashes = this.electrumX.getAddressTransactions(addressToScript(base58Address), false);

		List<byte[]> rawTransactions = new ArrayList<>();
		for (TransactionHash transactionInfo : transactionHashes) {
			byte[] rawTransaction = this.electrumX.getRawTransaction(HashCode.fromString(transactionInfo.txHash).asBytes());
			rawTransactions.add(rawTransaction);
		}

		return rawTransactions;
	}

	/**
	 * Returns transaction info for passed transaction hash.
	 * <p>
	 * @throws BitcoinException.NotFoundException if transaction unknown
	 * @throws BitcoinException if error occurs
	 */
	public BitcoinTransaction getTransaction(String txHash) throws BitcoinException {
		return this.electrumX.getTransaction(txHash);
	}

	/**
	 * Broadcasts raw transaction to Bitcoin network.
	 * <p>
	 * @throws BitcoinException if error occurs
	 */
	public void broadcastTransaction(Transaction transaction) throws BitcoinException {
		this.electrumX.broadcastTransaction(transaction.bitcoinSerialize());
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
		wallet.setUTXOProvider(new WalletAwareUTXOProvider(this, wallet, WalletAwareUTXOProvider.KeySearchMode.REQUEST_MORE_IF_ANY_SPENT));

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
	 * @return Bitcoin P2PKH address
	 * @throws BitcoinException if something went wrong
	 */
	public String getUnusedReceiveAddress(String xprv58) throws BitcoinException {
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

				List<UnspentOutput> unspentOutputs = this.electrumX.getUnspentOutputs(script, false);

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
					List<TransactionHash> historicTransactionHashes = this.electrumX.getAddressTransactions(script, false);

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

					List<UnspentOutput> unspentOutputs;
					try {
						unspentOutputs = btc.electrumX.getUnspentOutputs(script, false);
					} catch (BitcoinException e) {
						throw new UTXOProviderException(String.format("Unable to fetch unspent outputs for %s", address));
					}

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
						List<TransactionHash> historicTransactionHashes;
						try {
							historicTransactionHashes = btc.electrumX.getAddressTransactions(script, false);
						} catch (BitcoinException e) {
							throw new UTXOProviderException(String.format("Unable to fetch transaction history for %s", address));
						}

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
						List<TransactionOutput> transactionOutputs;
						try {
							transactionOutputs = btc.getOutputs(unspentOutput.hash);
						} catch (BitcoinException e) {
							throw new UTXOProviderException(String.format("Unable to fetch outputs for TX %s",
									HashCode.fromBytes(unspentOutput.hash)));
						}

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
			try {
				return btc.electrumX.getCurrentHeight();
			} catch (BitcoinException e) {
				throw new UTXOProviderException("Unable to determine Bitcoin chain height");
			}
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
