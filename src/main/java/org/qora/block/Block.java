package org.qora.block;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qora.account.Account;
import org.qora.account.PrivateKeyAccount;
import org.qora.account.PublicKeyAccount;
import org.qora.asset.Asset;
import org.qora.at.AT;
import org.qora.block.BlockChain.BlockTimingByHeight;
import org.qora.block.BlockChain.ShareByLevel;
import org.qora.controller.Controller;
import org.qora.crypto.Crypto;
import org.qora.data.account.AccountBalanceData;
import org.qora.data.account.AccountData;
import org.qora.data.account.ProxyForgerData;
import org.qora.data.at.ATData;
import org.qora.data.at.ATStateData;
import org.qora.data.block.BlockData;
import org.qora.data.block.BlockSummaryData;
import org.qora.data.block.BlockTransactionData;
import org.qora.data.network.OnlineAccountData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.ATRepository;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.TransactionRepository;
import org.qora.transaction.AtTransaction;
import org.qora.transaction.Transaction;
import org.qora.transaction.Transaction.ApprovalStatus;
import org.qora.transaction.Transaction.TransactionType;
import org.qora.transform.TransformationException;
import org.qora.transform.Transformer;
import org.qora.transform.block.BlockTransformer;
import org.qora.transform.transaction.TransactionTransformer;
import org.qora.utils.Base58;
import org.qora.utils.NTP;
import org.roaringbitmap.IntIterator;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;

import io.druid.extendedset.intset.ConciseSet;

public class Block {

	// Validation results
	public enum ValidationResult {
		OK(1),
		REFERENCE_MISSING(10),
		PARENT_DOES_NOT_EXIST(11),
		BLOCKCHAIN_NOT_EMPTY(12),
		PARENT_HAS_EXISTING_CHILD(13),
		TIMESTAMP_OLDER_THAN_PARENT(20),
		TIMESTAMP_IN_FUTURE(21),
		TIMESTAMP_MS_INCORRECT(22),
		TIMESTAMP_TOO_SOON(23),
		TIMESTAMP_INCORRECT(24),
		VERSION_INCORRECT(30),
		FEATURE_NOT_YET_RELEASED(31),
		GENERATOR_NOT_ACCEPTED(41),
		GENESIS_TRANSACTIONS_INVALID(50),
		TRANSACTION_TIMESTAMP_INVALID(51),
		TRANSACTION_INVALID(52),
		TRANSACTION_PROCESSING_FAILED(53),
		TRANSACTION_ALREADY_PROCESSED(54),
		TRANSACTION_NEEDS_APPROVAL(55),
		AT_STATES_MISMATCH(61),
		ONLINE_ACCOUNTS_INVALID(70),
		ONLINE_ACCOUNT_UNKNOWN(71),
		ONLINE_ACCOUNT_SIGNATURES_MISSING(72),
		ONLINE_ACCOUNT_SIGNATURES_MALFORMED(73),
		ONLINE_ACCOUNT_SIGNATURE_INCORRECT(74);

		public final int value;

		private final static Map<Integer, ValidationResult> map = stream(ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

		ValidationResult(int value) {
			this.value = value;
		}

		public static ValidationResult valueOf(int value) {
			return map.get(value);
		}
	}

	// Properties
	protected Repository repository;
	protected BlockData blockData;
	protected PublicKeyAccount generator;

	// Other properties
	private static final Logger LOGGER = LogManager.getLogger(Block.class);

	/** Number of left-shifts to apply to block's online accounts count when calculating block's weight. */
	private static final int ACCOUNTS_COUNT_SHIFT = Transformer.PUBLIC_KEY_LENGTH * 8;
	/** Number of left-shifts to apply to previous block's weight when calculating a chain's weight. */
	private static final int CHAIN_WEIGHT_SHIFT = 8;

	/** Sorted list of transactions attached to this block */
	protected List<Transaction> transactions;

	/** Remote/imported/loaded AT states */
	protected List<ATStateData> atStates;
	/** Locally-generated AT states */
	protected List<ATStateData> ourAtStates;
	/** Locally-generated AT fees */
	protected BigDecimal ourAtFees; // Generated locally

	/** Lazy-instantiated expanded info on block's online accounts. */
	class ExpandedAccount {
		final ProxyForgerData proxyForgerData;
		final boolean isRecipientAlsoForger;

		final Account forgerAccount;
		final AccountData forgerAccountData;
		final boolean isForgerFounder;
		final BigDecimal forgerQoraAmount;
		final int shareBin;

		final Account recipientAccount;
		final AccountData recipientAccountData;
		final boolean isRecipientFounder;

		ExpandedAccount(Repository repository, int accountIndex) throws DataException {
			final List<ShareByLevel> sharesByLevel = BlockChain.getInstance().getBlockSharesByLevel();

			this.proxyForgerData = repository.getAccountRepository().getProxyAccountByIndex(accountIndex);

			this.forgerAccount = new PublicKeyAccount(repository, this.proxyForgerData.getForgerPublicKey());
			this.recipientAccount = new Account(repository, this.proxyForgerData.getRecipient());

			AccountBalanceData qoraBalanceData = repository.getAccountRepository().getBalance(this.forgerAccount.getAddress(), Asset.LEGACY_QORA);
			if (qoraBalanceData != null && qoraBalanceData.getBalance() != null && qoraBalanceData.getBalance().compareTo(BigDecimal.ZERO) > 0)
				this.forgerQoraAmount = qoraBalanceData.getBalance();
			else
				this.forgerQoraAmount = null;

			this.forgerAccountData = repository.getAccountRepository().getAccount(this.forgerAccount.getAddress());
			this.isForgerFounder = Account.isFounder(forgerAccountData.getFlags());

			int currentShareBin = -1;

			if (!this.isForgerFounder)
				for (int s = 0; s < sharesByLevel.size(); ++s)
					if (sharesByLevel.get(s).levels.contains(this.forgerAccountData.getLevel())) {
						currentShareBin = s;
						break;
					}

			this.shareBin = currentShareBin;

			this.recipientAccountData = repository.getAccountRepository().getAccount(this.recipientAccount.getAddress());
			this.isRecipientFounder = Account.isFounder(recipientAccountData.getFlags());

			this.isRecipientAlsoForger = this.forgerAccountData.getAddress().equals(this.recipientAccountData.getAddress());
		}

		void distribute(BigDecimal accountAmount) throws DataException {
			final BigDecimal oneHundred = BigDecimal.valueOf(100L);

			if (this.forgerAccount.getAddress().equals(this.recipientAccount.getAddress())) {
				// forger & recipient the same - simpler case
				LOGGER.trace(() -> String.format("Forger/recipient account %s share: %s", this.forgerAccount.getAddress(), accountAmount.toPlainString()));
				this.forgerAccount.setConfirmedBalance(Asset.QORT, this.forgerAccount.getConfirmedBalance(Asset.QORT).add(accountAmount));
			} else {
				// forger & recipient different - extra work needed
				BigDecimal recipientAmount = accountAmount.multiply(this.proxyForgerData.getShare()).divide(oneHundred, RoundingMode.DOWN);
				BigDecimal forgerAmount = accountAmount.subtract(recipientAmount);

				LOGGER.trace(() -> String.format("Forger account %s share: %s", this.forgerAccount.getAddress(),  forgerAmount.toPlainString()));
				this.forgerAccount.setConfirmedBalance(Asset.QORT, this.forgerAccount.getConfirmedBalance(Asset.QORT).add(forgerAmount));

				LOGGER.trace(() -> String.format("Recipient account %s share: %s", this.recipientAccount.getAddress(), recipientAmount.toPlainString()));
				this.recipientAccount.setConfirmedBalance(Asset.QORT, this.recipientAccount.getConfirmedBalance(Asset.QORT).add(recipientAmount));
			}
		}
	}
	/** Always use getExpandedAccounts() to access this, as it's lazy-instantiated. */
	private List<ExpandedAccount> cachedExpandedAccounts = null;

	// Other useful constants

	private static final BigInteger MAX_DISTANCE;
	static {
		byte[] maxValue = new byte[Transformer.PUBLIC_KEY_LENGTH];
		Arrays.fill(maxValue, (byte) 0xFF);
		MAX_DISTANCE = new BigInteger(1, maxValue);
	}

	public static final ConciseSet EMPTY_ONLINE_ACCOUNTS = new ConciseSet();

	// Constructors

	/**
	 * Constructs Block-handling object without loading transactions and AT states.
	 * <p>
	 * Transactions and AT states are loaded on first call to getTransactions() or getATStates() respectively.
	 * 
	 * @param repository
	 * @param blockData
	 * @throws DataException
	 */
	public Block(Repository repository, BlockData blockData) throws DataException {
		this.repository = repository;
		this.blockData = blockData;
		this.generator = new PublicKeyAccount(repository, blockData.getGeneratorPublicKey());
	}

	/**
	 * Constructs Block-handling object using passed transaction and AT states.
	 * <p>
	 * This constructor typically used when receiving a serialized block over the network.
	 * 
	 * @param repository
	 * @param blockData
	 * @param transactions
	 * @param atStates
	 * @throws DataException
	 */
	public Block(Repository repository, BlockData blockData, List<TransactionData> transactions, List<ATStateData> atStates) throws DataException {
		this(repository, blockData);

		this.transactions = new ArrayList<Transaction>();

		BigDecimal totalFees = BigDecimal.ZERO.setScale(8);

		// We have to sum fees too
		for (TransactionData transactionData : transactions) {
			this.transactions.add(Transaction.fromData(repository, transactionData));
			totalFees = totalFees.add(transactionData.getFee());
		}

		this.atStates = atStates;
		for (ATStateData atState : atStates)
			totalFees = totalFees.add(atState.getFees());

		this.blockData.setTotalFees(totalFees);
	}

	/**
	 * Constructs Block-handling object with basic, initial values.
	 * <p>
	 * This constructor typically used when generating a new block.
	 * <p>
	 * Note that CIYAM ATs will be executed and AT-Transactions prepended to this block, along with AT state data and fees.
	 * 
	 * @param repository
	 * @param parentBlockData
	 * @param generator
	 * @throws DataException
	 */
	public Block(Repository repository, BlockData parentBlockData, PrivateKeyAccount generator) throws DataException {
		this.repository = repository;
		this.generator = generator;

		Block parentBlock = new Block(repository, parentBlockData);

		int version = parentBlock.getNextBlockVersion();
		byte[] reference = parentBlockData.getSignature();

		// Fetch our list of online accounts
		List<OnlineAccountData> onlineAccounts = Controller.getInstance().getOnlineAccounts();
		if (onlineAccounts.isEmpty())
			throw new IllegalStateException("No online accounts - not even our own?");

		// Find newest online accounts timestamp
		long onlineAccountsTimestamp = 0;
		for (OnlineAccountData onlineAccountData : onlineAccounts) {
			if (onlineAccountData.getTimestamp() > onlineAccountsTimestamp)
				onlineAccountsTimestamp = onlineAccountData.getTimestamp();
		}

		// Map using account index (in list of proxy forger accounts)
		Map<Integer, OnlineAccountData> indexedOnlineAccounts = new HashMap<>();
		for (OnlineAccountData onlineAccountData : onlineAccounts) {
			// Disregard online accounts with different timestamps
			if (onlineAccountData.getTimestamp() != onlineAccountsTimestamp)
				continue;

			int accountIndex = repository.getAccountRepository().getProxyAccountIndex(onlineAccountData.getPublicKey());
			indexedOnlineAccounts.put(accountIndex, onlineAccountData);
		}
		List<Integer> accountIndexes = new ArrayList<>(indexedOnlineAccounts.keySet());
		accountIndexes.sort(null);

		// Convert to compressed integer set
		ConciseSet onlineAccountsSet = new ConciseSet();
		onlineAccountsSet = onlineAccountsSet.convert(accountIndexes);
		byte[] encodedOnlineAccounts = BlockTransformer.encodeOnlineAccounts(onlineAccountsSet);
		int onlineAccountsCount = onlineAccountsSet.size();

		// Concatenate online account timestamp signatures (in correct order)
		byte[] onlineAccountsSignatures = new byte[onlineAccountsCount * Transformer.SIGNATURE_LENGTH];
		for (int i = 0; i < onlineAccountsCount; ++i) {
			Integer accountIndex = accountIndexes.get(i);
			OnlineAccountData onlineAccountData = indexedOnlineAccounts.get(accountIndex);
			System.arraycopy(onlineAccountData.getSignature(), 0, onlineAccountsSignatures, i * Transformer.SIGNATURE_LENGTH, Transformer.SIGNATURE_LENGTH);
		}

		byte[] generatorSignature;
		try {
			generatorSignature = generator.sign(BlockTransformer.getBytesForGeneratorSignature(parentBlockData.getGeneratorSignature(), generator, encodedOnlineAccounts));
		} catch (TransformationException e) {
			throw new DataException("Unable to calculate next block generator signature", e);
		}

		long timestamp = calcTimestamp(parentBlockData, generator.getPublicKey());

		int transactionCount = 0;
		byte[] transactionsSignature = null;
		int height = parentBlockData.getHeight() + 1;

		this.transactions = new ArrayList<Transaction>();

		int atCount = 0;
		BigDecimal atFees = BigDecimal.ZERO.setScale(8);
		BigDecimal totalFees = atFees;

		// This instance used for AT processing
		this.blockData = new BlockData(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp, 
				generator.getPublicKey(), generatorSignature, atCount, atFees,
				encodedOnlineAccounts, onlineAccountsCount, onlineAccountsTimestamp, onlineAccountsSignatures);

		// Requires this.blockData and this.transactions, sets this.ourAtStates and this.ourAtFees
		this.executeATs();

		atCount = this.ourAtStates.size();
		this.atStates = this.ourAtStates;
		atFees = this.ourAtFees;
		totalFees = atFees;

		// Rebuild blockData using post-AT-execute data
		this.blockData = new BlockData(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp, 
				generator.getPublicKey(), generatorSignature, atCount, atFees,
				encodedOnlineAccounts, onlineAccountsCount, onlineAccountsTimestamp, onlineAccountsSignatures);
	}

	/**
	 * Construct another block using this block as template, but with different generator account.
	 * <p>
	 * NOTE: uses the same transactions list, AT states, etc.
	 * 
	 * @param generator
	 * @return
	 * @throws DataException
	 */
	public Block regenerate(PrivateKeyAccount generator) throws DataException {
		Block newBlock = new Block(this.repository, this.blockData);
		newBlock.generator = generator;

		BlockData parentBlockData = this.getParent();

		// Copy AT state data
		newBlock.ourAtStates = this.ourAtStates;
		newBlock.atStates = newBlock.ourAtStates;
		newBlock.ourAtFees = this.ourAtFees;

		// Calculate new block timestamp
		int version = this.blockData.getVersion();
		byte[] reference = this.blockData.getReference();

		byte[] generatorSignature;
		try {
			generatorSignature = generator.sign(BlockTransformer.getBytesForGeneratorSignature(parentBlockData.getGeneratorSignature(), generator, this.blockData.getEncodedOnlineAccounts()));
		} catch (TransformationException e) {
			throw new DataException("Unable to calculate next block generator signature", e);
		}

		long timestamp = calcTimestamp(parentBlockData, generator.getPublicKey());

		newBlock.transactions = this.transactions;
		int transactionCount = this.blockData.getTransactionCount();
		BigDecimal totalFees = this.blockData.getTotalFees();
		byte[] transactionsSignature = null; // We'll calculate this later
		Integer height = this.blockData.getHeight();

		int atCount = newBlock.ourAtStates.size();
		BigDecimal atFees = newBlock.ourAtFees;

		byte[] encodedOnlineAccounts = this.blockData.getEncodedOnlineAccounts();
		int onlineAccountsCount = this.blockData.getOnlineAccountsCount();
		Long onlineAccountsTimestamp = this.blockData.getOnlineAccountsTimestamp();
		byte[] onlineAccountsSignatures = this.blockData.getOnlineAccountsSignatures();

		newBlock.blockData = new BlockData(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp,
				generator.getPublicKey(), generatorSignature, atCount, atFees, encodedOnlineAccounts, onlineAccountsCount, onlineAccountsTimestamp, onlineAccountsSignatures);

		// Resign to update transactions signature
		newBlock.sign();

		return newBlock;
	}

	// Getters/setters

	public BlockData getBlockData() {
		return this.blockData;
	}

	public PublicKeyAccount getGenerator() {
		return this.generator;
	}

	// More information

	/**
	 * Return composite block signature (generatorSignature + transactionsSignature).
	 * 
	 * @return byte[], or null if either component signature is null.
	 */
	public byte[] getSignature() {
		if (this.blockData.getGeneratorSignature() == null || this.blockData.getTransactionsSignature() == null)
			return null;

		return Bytes.concat(this.blockData.getGeneratorSignature(), this.blockData.getTransactionsSignature());
	}

	/**
	 * Return the next block's version.
	 * 
	 * @return 1, 2, 3 or 4
	 */
	public int getNextBlockVersion() {
		if (this.blockData.getHeight() == null)
			throw new IllegalStateException("Can't determine next block's version as this block has no height set");

		if (this.blockData.getHeight() < BlockChain.getInstance().getATReleaseHeight())
			return 1;
		else if (this.blockData.getTimestamp() < BlockChain.getInstance().getPowFixReleaseTimestamp())
			return 2;
		else if (this.blockData.getTimestamp() < BlockChain.getInstance().getQoraV2Timestamp())
			return 3;
		else
			return 4;
	}

	/**
	 * Return block's transactions.
	 * <p>
	 * If the block was loaded from repository then it's possible this method will call the repository to fetch the transactions if not done already.
	 * 
	 * @return
	 * @throws DataException
	 */
	public List<Transaction> getTransactions() throws DataException {
		// Already loaded?
		if (this.transactions != null)
			return this.transactions;

		// Allocate cache for results
		List<TransactionData> transactionsData = this.repository.getBlockRepository().getTransactionsFromSignature(this.blockData.getSignature());

		// The number of transactions fetched from repository should correspond with Block's transactionCount
		if (transactionsData.size() != this.blockData.getTransactionCount())
			throw new IllegalStateException("Block's transactions from repository do not match block's transaction count");

		this.transactions = new ArrayList<Transaction>();

		for (TransactionData transactionData : transactionsData)
			this.transactions.add(Transaction.fromData(this.repository, transactionData));

		return this.transactions;
	}

	/**
	 * Return block's AT states.
	 * <p>
	 * If the block was loaded from repository then it's possible this method will call the repository to fetch the AT states if not done already.
	 * <p>
	 * <b>Note:</b> AT states fetched from repository only contain summary info, not actual data like serialized state data or AT creation timestamps!
	 * 
	 * @return
	 * @throws DataException
	 */
	public List<ATStateData> getATStates() throws DataException {
		// Already loaded?
		if (this.atStates != null)
			return this.atStates;

		// If loading from repository, this block must have a height
		if (this.blockData.getHeight() == null)
			throw new IllegalStateException("Can't fetch block's AT states from repository without a block height");

		// Allocate cache for results
		List<ATStateData> atStateData = this.repository.getATRepository().getBlockATStatesAtHeight(this.blockData.getHeight());

		// The number of AT states fetched from repository should correspond with Block's atCount
		if (atStateData.size() != this.blockData.getATCount())
			throw new IllegalStateException("Block's AT states from repository do not match block's AT count");

		this.atStates = atStateData;

		return this.atStates;
	}

	/**
	 * Return expanded info on block's online accounts.
	 * <p>
	 * @throws DataException
	 */
	public List<ExpandedAccount> getExpandedAccounts() throws DataException {
		if (this.cachedExpandedAccounts != null)
			return this.cachedExpandedAccounts;

		ConciseSet accountIndexes = BlockTransformer.decodeOnlineAccounts(this.blockData.getEncodedOnlineAccounts());
		List<ExpandedAccount> expandedAccounts = new ArrayList<ExpandedAccount>();

		IntIterator iterator = accountIndexes.iterator();
		while (iterator.hasNext()) {
			int accountIndex = iterator.next();

			ExpandedAccount accountInfo = new ExpandedAccount(repository, accountIndex);
			expandedAccounts.add(accountInfo);
		}

		this.cachedExpandedAccounts = expandedAccounts;

		return this.cachedExpandedAccounts;
	}

	// Navigation

	/**
	 * Load parent block's data from repository via this block's reference.
	 * 
	 * @return parent's BlockData, or null if no parent found
	 * @throws DataException
	 */
	public BlockData getParent() throws DataException {
		byte[] reference = this.blockData.getReference();
		if (reference == null)
			return null;

		return this.repository.getBlockRepository().fromSignature(reference);
	}

	/**
	 * Load child block's data from repository via this block's signature.
	 * 
	 * @return child's BlockData, or null if no parent found
	 * @throws DataException
	 */
	public BlockData getChild() throws DataException {
		byte[] signature = this.blockData.getSignature();
		if (signature == null)
			return null;

		return this.repository.getBlockRepository().fromReference(signature);
	}

	// Processing

	/**
	 * Add a transaction to the block.
	 * <p>
	 * Used when constructing a new block during forging.
	 * <p>
	 * Requires block's {@code generator} being a {@code PrivateKeyAccount} so block's transactions signature can be recalculated.
	 * 
	 * @param transactionData
	 * @return true if transaction successfully added to block, false otherwise
	 * @throws IllegalStateException
	 *             if block's {@code generator} is not a {@code PrivateKeyAccount}.
	 */
	public boolean addTransaction(TransactionData transactionData) {
		// Can't add to transactions if we haven't loaded existing ones yet
		if (this.transactions == null)
			throw new IllegalStateException("Attempted to add transaction to partially loaded database Block");

		if (!(this.generator instanceof PrivateKeyAccount))
			throw new IllegalStateException("Block's generator has no private key");

		if (this.blockData.getGeneratorSignature() == null)
			throw new IllegalStateException("Cannot calculate transactions signature as block has no generator signature");

		// Already added? (Check using signature)
		if (this.transactions.stream().anyMatch(transaction -> Arrays.equals(transaction.getTransactionData().getSignature(), transactionData.getSignature())))
			return true;

		// Check there is space in block
		try {
			if (BlockTransformer.getDataLength(this) + TransactionTransformer.getDataLength(transactionData) > BlockChain.getInstance().getMaxBlockSize())
				return false;
		} catch (TransformationException e) {
			return false;
		}

		// Add to block
		this.transactions.add(Transaction.fromData(this.repository, transactionData));

		// Re-sort
		this.transactions.sort(Transaction.getComparator());

		// Update transaction count
		this.blockData.setTransactionCount(this.blockData.getTransactionCount() + 1);

		// Update totalFees
		this.blockData.setTotalFees(this.blockData.getTotalFees().add(transactionData.getFee()));

		// We've added a transaction, so recalculate transactions signature
		calcTransactionsSignature();

		return true;
	}

	/**
	 * Remove a transaction from the block.
	 * <p>
	 * Used when constructing a new block during forging.
	 * <p>
	 * Requires block's {@code generator} being a {@code PrivateKeyAccount} so block's transactions signature can be recalculated.
	 * 
	 * @param transactionData
	 * @throws IllegalStateException
	 *             if block's {@code generator} is not a {@code PrivateKeyAccount}.
	 */
	public void deleteTransaction(TransactionData transactionData) {
		// Can't add to transactions if we haven't loaded existing ones yet
		if (this.transactions == null)
			throw new IllegalStateException("Attempted to add transaction to partially loaded database Block");

		if (!(this.generator instanceof PrivateKeyAccount))
			throw new IllegalStateException("Block's generator has no private key");

		if (this.blockData.getGeneratorSignature() == null)
			throw new IllegalStateException("Cannot calculate transactions signature as block has no generator signature");

		// Attempt to remove from block (Check using signature)
		boolean wasElementRemoved = this.transactions.removeIf(transaction -> Arrays.equals(transaction.getTransactionData().getSignature(), transactionData.getSignature()));
		if (!wasElementRemoved)
			// Wasn't there - nothing more to do
			return;

		// Re-sort
		this.transactions.sort(Transaction.getComparator());

		// Update transaction count
		this.blockData.setTransactionCount(this.blockData.getTransactionCount() - 1);

		// Update totalFees
		this.blockData.setTotalFees(this.blockData.getTotalFees().subtract(transactionData.getFee()));

		// We've removed a transaction, so recalculate transactions signature
		calcTransactionsSignature();
	}

	/**
	 * Recalculate block's generator signature.
	 * <p>
	 * Requires block's {@code generator} being a {@code PrivateKeyAccount}.
	 * <p>
	 * Generator signature is made by the generator signing the following data:
	 * <p>
	 * previous block's generator signature + this block's generating balance + generator's public key
	 * <p>
	 * (Previous block's generator signature is extracted from this block's reference).
	 * 
	 * @throws IllegalStateException
	 *             if block's {@code generator} is not a {@code PrivateKeyAccount}.
	 * @throws RuntimeException
	 *             if somehow the generator signature cannot be calculated
	 */
	protected void calcGeneratorSignature() {
		if (!(this.generator instanceof PrivateKeyAccount))
			throw new IllegalStateException("Block's generator has no private key");

		try {
			this.blockData.setGeneratorSignature(((PrivateKeyAccount) this.generator).sign(BlockTransformer.getBytesForGeneratorSignature(this.blockData)));
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to calculate block's generator signature", e);
		}
	}

	/**
	 * Recalculate block's transactions signature.
	 * <p>
	 * Requires block's {@code generator} being a {@code PrivateKeyAccount}.
	 * 
	 * @throws IllegalStateException
	 *             if block's {@code generator} is not a {@code PrivateKeyAccount}.
	 * @throws RuntimeException
	 *             if somehow the transactions signature cannot be calculated
	 */
	protected void calcTransactionsSignature() {
		if (!(this.generator instanceof PrivateKeyAccount))
			throw new IllegalStateException("Block's generator has no private key");

		try {
			this.blockData.setTransactionsSignature(((PrivateKeyAccount) this.generator).sign(BlockTransformer.getBytesForTransactionsSignature(this)));
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to calculate block's transactions signature", e);
		}
	}

	public static byte[] calcIdealGeneratorPublicKey(int parentBlockHeight, byte[] parentBlockSignature) {
		return Crypto.digest(Bytes.concat(Longs.toByteArray(parentBlockHeight), parentBlockSignature));
	}

	public static byte[] calcHeightPerturbedPublicKey(int height, byte[] publicKey) {
		return Crypto.digest(Bytes.concat(Longs.toByteArray(height), publicKey));
	}

	public static BigInteger calcKeyDistance(int parentHeight, byte[] parentBlockSignature, byte[] publicKey) {
		byte[] idealKey = calcIdealGeneratorPublicKey(parentHeight, parentBlockSignature);
		byte[] perturbedKey = calcHeightPerturbedPublicKey(parentHeight + 1, publicKey);

		BigInteger keyDistance = MAX_DISTANCE.subtract(new BigInteger(idealKey).subtract(new BigInteger(perturbedKey)).abs());
		return keyDistance;
	}

	public static BigInteger calcBlockWeight(int parentHeight, byte[] parentBlockSignature, BlockSummaryData blockSummaryData) {
		BigInteger keyDistance = calcKeyDistance(parentHeight, parentBlockSignature, blockSummaryData.getGeneratorPublicKey());
		BigInteger weight = BigInteger.valueOf(blockSummaryData.getOnlineAccountsCount()).shiftLeft(ACCOUNTS_COUNT_SHIFT).add(keyDistance);
		return weight;
	}

	public static BigInteger calcChainWeight(int commonBlockHeight, byte[] commonBlockSignature, List<BlockSummaryData> blockSummaries) {
		BigInteger cumulativeWeight = BigInteger.ZERO;
		int parentHeight = commonBlockHeight;
		byte[] parentBlockSignature = commonBlockSignature;

		for (BlockSummaryData blockSummaryData : blockSummaries) {
			cumulativeWeight = cumulativeWeight.shiftLeft(CHAIN_WEIGHT_SHIFT).add(calcBlockWeight(parentHeight, parentBlockSignature, blockSummaryData));
			parentHeight = blockSummaryData.getHeight();
			parentBlockSignature = blockSummaryData.getSignature();
		}

		return cumulativeWeight;
	}

	/**
	 * Returns timestamp based on previous block and this block's generator.
	 * <p>
	 * Uses same proportion of this block's generator from 'ideal' generator
	 * with min to max target block periods, added to previous block's timestamp.
	 * <p>
	 * Example:<br>
	 * This block's generator is 20% of max distance from 'ideal' generator.<br>
	 * Min/Max block periods are 30s and 90s respectively.<br>
	 * 20% of (90s - 30s) is 12s<br>
	 * So this block's timestamp is previous block's timestamp + 30s + 12s.
	 */
	public static long calcTimestamp(BlockData parentBlockData, byte[] generatorPublicKey) {
		BigInteger distance = calcKeyDistance(parentBlockData.getHeight(), parentBlockData.getSignature(), generatorPublicKey);
		final int thisHeight = parentBlockData.getHeight() + 1;
		BlockTimingByHeight blockTiming = BlockChain.getInstance().getBlockTimingByHeight(thisHeight);

		double ratio = new BigDecimal(distance).divide(new BigDecimal(MAX_DISTANCE), 40, RoundingMode.DOWN).doubleValue();

		// Use power transform on ratio to spread out smaller values for bigger effect
		double transformed = Math.pow(ratio, blockTiming.power);

		long timeOffset = Double.valueOf(blockTiming.deviation * 2.0 * transformed).longValue();

		return parentBlockData.getTimestamp() + blockTiming.target - blockTiming.deviation + timeOffset;
	}

	public static long calcMinimumTimestamp(BlockData parentBlockData) {
		final int thisHeight = parentBlockData.getHeight() + 1;
		BlockTimingByHeight blockTiming = BlockChain.getInstance().getBlockTimingByHeight(thisHeight);
		return parentBlockData.getTimestamp() + blockTiming.target - blockTiming.deviation;
	}

	/**
	 * Recalculate block's generator and transactions signatures, thus giving block full signature.
	 * <p>
	 * Note: Block instance must have been constructed with a <tt>PrivateKeyAccount generator</tt> or this call will throw an <tt>IllegalStateException</tt>.
	 * 
	 * @throws IllegalStateException
	 *             if block's {@code generator} is not a {@code PrivateKeyAccount}.
	 */
	public void sign() {
		this.calcGeneratorSignature();
		this.calcTransactionsSignature();

		this.blockData.setSignature(this.getSignature());
	}

	/**
	 * Returns whether this block's signatures are valid.
	 * 
	 * @return true if both generator and transaction signatures are valid, false otherwise
	 */
	public boolean isSignatureValid() {
		try {
			// Check generator's signature first
			if (!this.generator.verify(this.blockData.getGeneratorSignature(), BlockTransformer.getBytesForGeneratorSignature(this.blockData)))
				return false;

			// Check transactions signature
			if (!this.generator.verify(this.blockData.getTransactionsSignature(), BlockTransformer.getBytesForTransactionsSignature(this)))
				return false;
		} catch (TransformationException e) {
			return false;
		}

		return true;
	}

	/**
	 * Returns whether Block's timestamp is valid.
	 * <p>
	 * Used by BlockGenerator to check whether it's time to forge new block,
	 * and also used by Block.isValid for checks (if not a testchain).
	 * 
	 * @return ValidationResult.OK if timestamp valid, or some other ValidationResult otherwise.
	 * @throws DataException
	 */
	public ValidationResult isTimestampValid() throws DataException {
		BlockData parentBlockData = this.repository.getBlockRepository().fromSignature(this.blockData.getReference());
		if (parentBlockData == null)
			return ValidationResult.PARENT_DOES_NOT_EXIST;

		// Check timestamp is newer than parent timestamp
		if (this.blockData.getTimestamp() <= parentBlockData.getTimestamp())
			return ValidationResult.TIMESTAMP_OLDER_THAN_PARENT;

		// Check timestamp is not in the future (within configurable margin)
		// We don't need to check NTP.getTime() for null as we shouldn't reach here if that is already the case
		if (this.blockData.getTimestamp() - BlockChain.getInstance().getBlockTimestampMargin() > NTP.getTime())
			return ValidationResult.TIMESTAMP_IN_FUTURE;

		// Check timestamp is at least minimum based on parent block
		if (this.blockData.getTimestamp() < Block.calcMinimumTimestamp(parentBlockData))
			return ValidationResult.TIMESTAMP_TOO_SOON;

		long expectedTimestamp = calcTimestamp(parentBlockData, this.blockData.getGeneratorPublicKey());
		if (this.blockData.getTimestamp() != expectedTimestamp)
			return ValidationResult.TIMESTAMP_INCORRECT;

		return ValidationResult.OK;
	}

	public ValidationResult areOnlineAccountsValid() throws DataException {
		// Doesn't apply for Genesis block!
		if (this.blockData.getHeight() != null && this.blockData.getHeight() == 1)
			return ValidationResult.OK;

		// Expand block's online accounts indexes into actual accounts
		ConciseSet accountIndexes = BlockTransformer.decodeOnlineAccounts(this.blockData.getEncodedOnlineAccounts());
		// We use count of online accounts to validate decoded account indexes
		if (accountIndexes.size() != this.blockData.getOnlineAccountsCount())
			return ValidationResult.ONLINE_ACCOUNTS_INVALID;

		List<ProxyForgerData> expandedAccounts = new ArrayList<>();

		IntIterator iterator = accountIndexes.iterator();
		while (iterator.hasNext()) {
			int accountIndex = iterator.next();
			ProxyForgerData proxyAccountData = repository.getAccountRepository().getProxyAccountByIndex(accountIndex);

			// Check that claimed online account actually exists
			if (proxyAccountData == null)
				return ValidationResult.ONLINE_ACCOUNT_UNKNOWN;

			expandedAccounts.add(proxyAccountData);
		}

		// Possibly check signatures if block is recent
		long signatureRequirementThreshold = NTP.getTime() - BlockChain.getInstance().getOnlineAccountSignaturesMinLifetime();
		if (this.blockData.getTimestamp() >= signatureRequirementThreshold) {
			if (this.blockData.getOnlineAccountsSignatures() == null || this.blockData.getOnlineAccountsSignatures().length == 0)
				return ValidationResult.ONLINE_ACCOUNT_SIGNATURES_MISSING;

			if (this.blockData.getOnlineAccountsSignatures().length != expandedAccounts.size() * Transformer.SIGNATURE_LENGTH)
				return ValidationResult.ONLINE_ACCOUNT_SIGNATURES_MALFORMED;

			// Check signatures
			List<byte[]> onlineAccountsSignatures = BlockTransformer.decodeTimestampSignatures(this.blockData.getOnlineAccountsSignatures());
			byte[] message = Longs.toByteArray(this.blockData.getOnlineAccountsTimestamp());

			for (int i = 0; i < onlineAccountsSignatures.size(); ++i) {
				PublicKeyAccount account = new PublicKeyAccount(null, expandedAccounts.get(i).getProxyPublicKey());
				byte[] signature = onlineAccountsSignatures.get(i);

				if (!account.verify(signature, message))
					return ValidationResult.ONLINE_ACCOUNT_SIGNATURE_INCORRECT;
			}
		}

		return ValidationResult.OK;
	}


	/**
	 * Returns whether Block is valid.
	 * <p>
	 * Performs various tests like checking for parent block, correct block timestamp, version, generating balance, etc.
	 * <p>
	 * Checks block's transactions by testing their validity then processing them.<br>
	 * Hence uses a repository savepoint during execution.
	 * 
	 * @return ValidationResult.OK if block is valid, or some other ValidationResult otherwise.
	 * @throws DataException
	 */
	public ValidationResult isValid() throws DataException {
		// Check parent block exists
		if (this.blockData.getReference() == null)
			return ValidationResult.REFERENCE_MISSING;

		BlockData parentBlockData = this.repository.getBlockRepository().fromSignature(this.blockData.getReference());
		if (parentBlockData == null)
			return ValidationResult.PARENT_DOES_NOT_EXIST;

		Block parentBlock = new Block(this.repository, parentBlockData);

		// Check parent doesn't already have a child block
		if (parentBlock.getChild() != null)
			return ValidationResult.PARENT_HAS_EXISTING_CHILD;

		// Check timestamp is newer than parent timestamp
		if (this.blockData.getTimestamp() <= parentBlockData.getTimestamp())
			return ValidationResult.TIMESTAMP_OLDER_THAN_PARENT;

		// These checks are disabled for testchains
		if (!BlockChain.getInstance().isTestChain()) {
			ValidationResult timestampResult = this.isTimestampValid();

			if (timestampResult != ValidationResult.OK)
				return timestampResult;
		}

		// Check block version
		if (this.blockData.getVersion() != parentBlock.getNextBlockVersion())
			return ValidationResult.VERSION_INCORRECT;
		if (this.blockData.getVersion() < 2 && this.blockData.getATCount() != 0)
			return ValidationResult.FEATURE_NOT_YET_RELEASED;

		// Check generator is allowed to forge this block
		if (!isGeneratorValidToForge(parentBlock))
			return ValidationResult.GENERATOR_NOT_ACCEPTED;

		// Online Accounts
		ValidationResult onlineAccountsResult = this.areOnlineAccountsValid();
		if (onlineAccountsResult != ValidationResult.OK)
			return onlineAccountsResult;

		// CIYAM ATs
		if (this.blockData.getATCount() != 0) {
			// Locally generated AT states should be valid so no need to re-execute them
			if (this.ourAtStates != this.getATStates()) {
				// For old v1 CIYAM ATs we blindly accept them
				if (this.blockData.getVersion() < 4) {
					this.ourAtStates = this.atStates;
					this.ourAtFees = this.blockData.getATFees();
				} else {
					// Generate local AT states for comparison
					this.executeATs();
				}

				// Check locally generated AT states against ones received from elsewhere

				if (this.ourAtStates.size() != this.blockData.getATCount())
					return ValidationResult.AT_STATES_MISMATCH;

				if (this.ourAtFees.compareTo(this.blockData.getATFees()) != 0)
					return ValidationResult.AT_STATES_MISMATCH;

				// Note: this.atStates fully loaded thanks to this.getATStates() call above
				for (int s = 0; s < this.atStates.size(); ++s) {
					ATStateData ourAtState = this.ourAtStates.get(s);
					ATStateData theirAtState = this.atStates.get(s);

					if (!ourAtState.getATAddress().equals(theirAtState.getATAddress()))
						return ValidationResult.AT_STATES_MISMATCH;

					if (!ourAtState.getStateHash().equals(theirAtState.getStateHash()))
						return ValidationResult.AT_STATES_MISMATCH;

					if (ourAtState.getFees().compareTo(theirAtState.getFees()) != 0)
						return ValidationResult.AT_STATES_MISMATCH;
				}
			}
		}

		// Check transactions
		try {
			// Create repository savepoint here so we can rollback to it after testing transactions
			repository.setSavepoint();

			for (Transaction transaction : this.getTransactions()) {
				TransactionData transactionData = transaction.getTransactionData();

				// GenesisTransactions are not allowed (GenesisBlock overrides isValid() to allow them)
				if (transactionData.getType() == TransactionType.GENESIS || transactionData.getType() == TransactionType.ACCOUNT_FLAGS)
					return ValidationResult.GENESIS_TRANSACTIONS_INVALID;

				// Check timestamp and deadline
				if (transactionData.getTimestamp() > this.blockData.getTimestamp()
						|| transaction.getDeadline() <= this.blockData.getTimestamp())
					return ValidationResult.TRANSACTION_TIMESTAMP_INVALID;

				// Check transaction isn't already included in a block
				if (this.repository.getTransactionRepository().isConfirmed(transactionData.getSignature()))
					return ValidationResult.TRANSACTION_ALREADY_PROCESSED;

				// Check transaction has correct reference, etc.
				if (!transaction.hasValidReference()) {
					LOGGER.debug("Error during transaction validation, tx " + Base58.encode(transactionData.getSignature()) + ": INVALID_REFERENCE");
					return ValidationResult.TRANSACTION_INVALID;
				}

				// Check transaction is even valid
				// NOTE: in Gen1 there was an extra block height passed to DeployATTransaction.isValid
				Transaction.ValidationResult validationResult = transaction.isValid();
				if (validationResult != Transaction.ValidationResult.OK) {
					LOGGER.debug("Error during transaction validation, tx " + Base58.encode(transactionData.getSignature()) + ": "
							+ validationResult.name());
					return ValidationResult.TRANSACTION_INVALID;
				}

				// Check transaction can even be processed
				validationResult = transaction.isProcessable();
				if (validationResult != Transaction.ValidationResult.OK) {
					LOGGER.debug("Error during transaction validation, tx " + Base58.encode(transactionData.getSignature()) + ": "
							+ validationResult.name());
					return ValidationResult.TRANSACTION_INVALID;
				}

				// Process transaction to make sure other transactions validate properly
				try {
					// Only process transactions that don't require group-approval.
					// Group-approval transactions are dealt with later.
					if (transactionData.getApprovalStatus() == ApprovalStatus.NOT_REQUIRED)
						transaction.process();

					// Regardless of group-approval, update relevant info for creator (e.g. lastReference)
					transaction.processReferencesAndFees();
				} catch (Exception e) {
					LOGGER.error("Exception during transaction validation, tx " + Base58.encode(transactionData.getSignature()), e);
					e.printStackTrace();
					return ValidationResult.TRANSACTION_PROCESSING_FAILED;
				}
			}
		} catch (DataException e) {
			return ValidationResult.TRANSACTION_INVALID;
		} finally {
			// Rollback repository changes made by test-processing transactions above
			try {
				this.repository.rollbackToSavepoint();
			} catch (DataException e) {
				/*
				 * Rollback failure most likely due to prior DataException, so discard this DataException. Prior DataException propagates to caller.
				 */
			}
		}

		// Block is valid
		return ValidationResult.OK;
	}

	/**
	 * Execute CIYAM ATs for this block.
	 * <p>
	 * This needs to be done locally for all blocks, regardless of origin.<br>
	 * Typically called by <tt>isValid()</tt> or new block constructor.
	 * <p>
	 * After calling, AT-generated transactions are prepended to the block's transactions and AT state data is generated.
	 * <p>
	 * Updates <tt>this.ourAtStates</tt> (local version) and <tt>this.ourAtFees</tt> (remote/imported/loaded version).
	 * <p>
	 * Note: this method does not store new AT state data into repository - that is handled by <tt>process()</tt>.
	 * <p>
	 * This method is not needed if fetching an existing block from the repository as AT state data will be loaded from repository as well.
	 * 
	 * @see #isValid()
	 * 
	 * @throws DataException
	 * 
	 */
	private void executeATs() throws DataException {
		// We're expecting a lack of AT state data at this point.
		if (this.ourAtStates != null)
			throw new IllegalStateException("Attempted to execute ATs when block's local AT state data already exists");

		// AT-Transactions generated by running ATs, to be prepended to block's transactions
		List<AtTransaction> allATTransactions = new ArrayList<AtTransaction>();

		this.ourAtStates = new ArrayList<ATStateData>();
		this.ourAtFees = BigDecimal.ZERO.setScale(8);

		// Find all executable ATs, ordered by earliest creation date first
		List<ATData> executableATs = this.repository.getATRepository().getAllExecutableATs();

		// Run each AT, appends AT-Transactions and corresponding AT states, to our lists
		for (ATData atData : executableATs) {
			AT at = new AT(this.repository, atData);
			List<AtTransaction> atTransactions = at.run(this.blockData.getTimestamp());

			allATTransactions.addAll(atTransactions);

			ATStateData atStateData = at.getATStateData();
			this.ourAtStates.add(atStateData);

			this.ourAtFees = this.ourAtFees.add(atStateData.getFees());
		}

		// Prepend our entire AT-Transactions/states to block's transactions
		this.transactions.addAll(0, allATTransactions);

		// Re-sort
		this.transactions.sort(Transaction.getComparator());

		// Update transaction count
		this.blockData.setTransactionCount(this.blockData.getTransactionCount() + 1);

		// We've added transactions, so recalculate transactions signature
		calcTransactionsSignature();
	}

	/** Returns whether block's generator is actually allowed to forge this block. */
	protected boolean isGeneratorValidToForge(Block parentBlock) throws DataException {
		// Block's generator public key must be known proxy forging public key
		ProxyForgerData proxyForgerData = this.repository.getAccountRepository().getProxyForgeData(this.blockData.getGeneratorPublicKey());
		if (proxyForgerData == null)
			return false;

		Account forger = new PublicKeyAccount(this.repository, proxyForgerData.getForgerPublicKey());
		return forger.canForge();
	}

	/**
	 * Process block, and its transactions, adding them to the blockchain.
	 * 
	 * @throws DataException
	 */
	public void process() throws DataException {
		// Set our block's height
		int blockchainHeight = this.repository.getBlockRepository().getBlockchainHeight();
		this.blockData.setHeight(blockchainHeight + 1);

		if (this.blockData.getHeight() > 1) {
			// Increase account levels
			increaseAccountLevels();

			// Block rewards go before transactions processed
			processBlockRewards();
		}

		// Process transactions (we'll link them to this block after saving the block itself)
		processTransactions();

		// Group-approval transactions
		processGroupApprovalTransactions();

		if (this.blockData.getHeight() > 1)
			// Give transaction fees to generator/proxy
			rewardTransactionFees();

		// Process AT fees and save AT states into repository
		processAtFeesAndStates();

		// Link block into blockchain by fetching signature of highest block and setting that as our reference
		BlockData latestBlockData = this.repository.getBlockRepository().fromHeight(blockchainHeight);
		if (latestBlockData != null)
			this.blockData.setReference(latestBlockData.getSignature());

		// Save block
		this.repository.getBlockRepository().save(this.blockData);

		// Link transactions to this block, thus removing them from unconfirmed transactions list.
		// Also update "transaction participants" in repository for "transactions involving X" support in API
		linkTransactionsToBlock();
	}

	protected void increaseAccountLevels() throws DataException {
		List<Integer> blocksNeededByLevel = BlockChain.getInstance().getBlocksNeededByLevel();

		// Pre-calculate cumulative blocks required for each level
		int cumulativeBlocks = 0;
		int[] cumulativeBlocksByLevel = new int[blocksNeededByLevel.size() + 1];
		for (int level = 0; level < cumulativeBlocksByLevel.length; ++level) {
			cumulativeBlocksByLevel[level] = cumulativeBlocks;

			if (level < blocksNeededByLevel.size())
				cumulativeBlocks += blocksNeededByLevel.get(level);
		}

		List<ExpandedAccount> expandedAccounts = this.getExpandedAccounts();

		// We need to do this for both forgers and recipients
		this.increaseAccountLevels(expandedAccounts, cumulativeBlocksByLevel,
				expandedAccount -> expandedAccount.isForgerFounder,
				expandedAccount -> expandedAccount.forgerAccountData);

		this.increaseAccountLevels(expandedAccounts, cumulativeBlocksByLevel,
				expandedAccount -> expandedAccount.isRecipientFounder,
				expandedAccount -> expandedAccount.recipientAccountData);
	}

	private void increaseAccountLevels(List<ExpandedAccount> expandedAccounts, int[] cumulativeBlocksByLevel,
			Predicate<ExpandedAccount> isFounder, Function<ExpandedAccount, AccountData> getAccountData) throws DataException {
		final boolean isProcessingRecipients = getAccountData.apply(expandedAccounts.get(0)) == expandedAccounts.get(0).recipientAccountData;

		// Increase blocks generated count for all accounts
		for (int a = 0; a < expandedAccounts.size(); ++a) {
			ExpandedAccount expandedAccount = expandedAccounts.get(a);

			// Don't increase twice if recipient is also forger.
			if (isProcessingRecipients && expandedAccount.isRecipientAlsoForger)
				continue;

			AccountData accountData = getAccountData.apply(expandedAccount);

			accountData.setBlocksGenerated(accountData.getBlocksGenerated() + 1);
			repository.getAccountRepository().setBlocksGenerated(accountData);
			LOGGER.trace(() -> String.format("Block generator %s has generated %d block%s", accountData.getAddress(), accountData.getBlocksGenerated(), (accountData.getBlocksGenerated() != 1 ? "s" : "")));
		}

		// We are only interested in accounts that are NOT founders and NOT already highest level
		final int maximumLevel = cumulativeBlocksByLevel.length - 1;
		List<ExpandedAccount> candidateAccounts = expandedAccounts.stream().filter(expandedAccount -> !isFounder.test(expandedAccount) && getAccountData.apply(expandedAccount).getLevel() < maximumLevel).collect(Collectors.toList());

		for (int c = 0; c < candidateAccounts.size(); ++c) {
			ExpandedAccount expandedAccount = candidateAccounts.get(c);
			final AccountData accountData = getAccountData.apply(expandedAccount);

			final int effectiveBlocksGenerated = cumulativeBlocksByLevel[accountData.getInitialLevel()] + accountData.getBlocksGenerated();

			for (int newLevel = cumulativeBlocksByLevel.length - 1; newLevel > 0; --newLevel)
				if (effectiveBlocksGenerated >= cumulativeBlocksByLevel[newLevel]) {
					if (newLevel > accountData.getLevel()) {
						// Account has increased in level!
						accountData.setLevel(newLevel);
						repository.getAccountRepository().setLevel(accountData);
						LOGGER.trace(() -> String.format("Block generator %s bumped to level %d", accountData.getAddress(), accountData.getLevel()));
					}

					break;
				}
		}
	}

	protected void processBlockRewards() throws DataException {
		BigDecimal reward = BlockChain.getInstance().getRewardAtHeight(this.blockData.getHeight());

		// No reward for our height?
		if (reward == null)
			return;

		distributeByAccountLevel(reward);
	}

	protected void processTransactions() throws DataException {
		// Process transactions (we'll link them to this block after saving the block itself)
		// AT-generated transactions are already prepended to our transactions at this point.
		List<Transaction> transactions = this.getTransactions();

		for (Transaction transaction : transactions) {
			TransactionData transactionData = transaction.getTransactionData();

			// AT_TRANSACTIONs are created locally and need saving into repository before processing
			if (transactionData.getType() == TransactionType.AT)
				this.repository.getTransactionRepository().save(transactionData);

			// Only process transactions that don't require group-approval.
			// Group-approval transactions are dealt with later.
			if (transactionData.getApprovalStatus() == ApprovalStatus.NOT_REQUIRED)
				transaction.process();

			// Regardless of group-approval, update relevant info for creator (e.g. lastReference)
			transaction.processReferencesAndFees();
		}
	}

	protected void processGroupApprovalTransactions() throws DataException {
		TransactionRepository transactionRepository = this.repository.getTransactionRepository();

		// Search for pending transactions that have now expired
		List<TransactionData> approvalExpiringTransactions = transactionRepository.getApprovalExpiringTransactions(this.blockData.getHeight());

		for (TransactionData transactionData : approvalExpiringTransactions) {
			transactionData.setApprovalStatus(ApprovalStatus.EXPIRED);
			transactionRepository.save(transactionData);

			// Update group-approval decision height for transaction in repository
			transactionRepository.updateApprovalHeight(transactionData.getSignature(), this.blockData.getHeight());
		}

		// Search for pending transactions within min/max block delay range
		List<TransactionData> approvalPendingTransactions = transactionRepository.getApprovalPendingTransactions(this.blockData.getHeight());

		for (TransactionData transactionData : approvalPendingTransactions) {
			Transaction transaction = Transaction.fromData(this.repository, transactionData);

			// something like:
			Boolean isApproved = transaction.getApprovalDecision();

			if (isApproved == null)
				continue; // approve/reject threshold not yet met

			// Update group-approval decision height for transaction in repository
			transactionRepository.updateApprovalHeight(transactionData.getSignature(), this.blockData.getHeight());

			if (!isApproved) {
				// REJECT
				transactionData.setApprovalStatus(ApprovalStatus.REJECTED);
				transactionRepository.save(transactionData);
				continue;
			}

			// Approved, but check transaction can still be processed
			if (transaction.isProcessable() != Transaction.ValidationResult.OK) {
				transactionData.setApprovalStatus(ApprovalStatus.INVALID);
				transactionRepository.save(transactionData);
				continue;
			}

			// APPROVED, in which case do transaction.process();
			transactionData.setApprovalStatus(ApprovalStatus.APPROVED);
			transactionRepository.save(transactionData);

			transaction.process();
		}
	}

	protected void rewardTransactionFees() throws DataException {
		BigDecimal blockFees = this.blockData.getTotalFees();

		// No transaction fees?
		if (blockFees.compareTo(BigDecimal.ZERO) <= 0)
			return;

		distributeByAccountLevel(blockFees);
	}

	protected void processAtFeesAndStates() throws DataException {
		ATRepository atRepository = this.repository.getATRepository();

		for (ATStateData atState : this.getATStates()) {
			Account atAccount = new Account(this.repository, atState.getATAddress());

			// Subtract AT-generated fees from AT accounts
			atAccount.setConfirmedBalance(Asset.QORT, atAccount.getConfirmedBalance(Asset.QORT).subtract(atState.getFees()));

			atRepository.save(atState);
		}
	}

	protected void linkTransactionsToBlock() throws DataException {
		TransactionRepository transactionRepository = this.repository.getTransactionRepository();

		for (int sequence = 0; sequence < transactions.size(); ++sequence) {
			Transaction transaction = transactions.get(sequence);
			TransactionData transactionData = transaction.getTransactionData();

			// Link transaction to this block
			BlockTransactionData blockTransactionData = new BlockTransactionData(this.getSignature(), sequence,
					transactionData.getSignature());
			this.repository.getBlockRepository().save(blockTransactionData);

			// Update transaction's height in repository
			transactionRepository.updateBlockHeight(transactionData.getSignature(), this.blockData.getHeight());

			// Update local transactionData's height too
			transaction.getTransactionData().setBlockHeight(this.blockData.getHeight());

			// No longer unconfirmed
			transactionRepository.confirmTransaction(transactionData.getSignature());

			List<Account> participants = transaction.getInvolvedAccounts();
			List<String> participantAddresses = participants.stream().map(account -> account.getAddress()).collect(Collectors.toList());
			transactionRepository.saveParticipants(transactionData, participantAddresses);
		}
	}

	/**
	 * Removes block from blockchain undoing transactions and adding them to unconfirmed pile.
	 * 
	 * @throws DataException
	 */
	public void orphan() throws DataException {
		if (this.blockData.getHeight() > 1)
			// Deduct any transaction fees from generator/proxy
			deductTransactionFees();

		// Orphan, and unlink, transactions from this block
		orphanTransactionsFromBlock();

		// Undo any group-approval decisions that happen at this block
		orphanGroupApprovalTransactions();

		if (this.blockData.getHeight() > 1) {
			// Block rewards removed after transactions undone
			orphanBlockRewards();

			// Decrease account levels
			decreaseAccountLevels();
		}

		// Return AT fees and delete AT states from repository
		orphanAtFeesAndStates();

		// Delete block from blockchain
		this.repository.getBlockRepository().delete(this.blockData);
		this.blockData.setHeight(null);
	}

	protected void orphanTransactionsFromBlock() throws DataException {
		TransactionRepository transactionRepository = this.repository.getTransactionRepository();

		// AT-generated transactions are already added to our transactions so no special handling is needed here.
		List<Transaction> transactions = this.getTransactions();

		for (int sequence = transactions.size() - 1; sequence >= 0; --sequence) {
			Transaction transaction = transactions.get(sequence);
			TransactionData transactionData = transaction.getTransactionData();

			// Orphan transaction
			// Only orphan transactions that didn't require group-approval.
			// Group-approval transactions are dealt with later.
			if (transactionData.getApprovalStatus() == ApprovalStatus.NOT_REQUIRED)
				transaction.orphan();

			// Regardless of group-approval, update relevant info for creator (e.g. lastReference)
			transaction.orphanReferencesAndFees();

			// Unlink transaction from this block
			BlockTransactionData blockTransactionData = new BlockTransactionData(this.getSignature(), sequence,
					transactionData.getSignature());
			this.repository.getBlockRepository().delete(blockTransactionData);

			// Add to unconfirmed pile and remove height, or delete if AT_TRANSACTION
			if (transaction.getTransactionData().getType() == TransactionType.AT) {
				transactionRepository.delete(transactionData);
			} else {
				// Add to unconfirmed pile
				transactionRepository.unconfirmTransaction(transactionData);

				// Unset height
				transactionRepository.updateBlockHeight(transactionData.getSignature(), null);
			}

			transactionRepository.deleteParticipants(transactionData);
		}
	}

	protected void orphanGroupApprovalTransactions() throws DataException {
		TransactionRepository transactionRepository = this.repository.getTransactionRepository();

		// Find all transactions where decision happened at this block height
		List<TransactionData> transactions = transactionRepository.getApprovalTransactionDecidedAtHeight(this.blockData.getHeight());

		for (TransactionData transactionData : transactions) {
			// Orphan/un-process transaction (if approved)
			Transaction transaction = Transaction.fromData(repository, transactionData);
			if (transactionData.getApprovalStatus() == ApprovalStatus.APPROVED)
				transaction.orphan();

			// Revert back to PENDING
			transactionData.setApprovalStatus(ApprovalStatus.PENDING);
			transactionRepository.save(transactionData);

			// Remove group-approval decision height
			transactionRepository.updateApprovalHeight(transactionData.getSignature(), null);
		}
	}

	protected void orphanBlockRewards() throws DataException {
		BigDecimal reward = BlockChain.getInstance().getRewardAtHeight(this.blockData.getHeight());

		// No reward for our height?
		if (reward == null)
			return;

		distributeByAccountLevel(reward.negate());
	}

	protected void deductTransactionFees() throws DataException {
		BigDecimal blockFees = this.blockData.getTotalFees();

		// No transaction fees?
		if (blockFees.compareTo(BigDecimal.ZERO) <= 0)
			return;

		distributeByAccountLevel(blockFees.negate());
	}

	protected void orphanAtFeesAndStates() throws DataException {
		ATRepository atRepository = this.repository.getATRepository();
		for (ATStateData atState : this.getATStates()) {
			Account atAccount = new Account(this.repository, atState.getATAddress());

			// Return AT-generated fees to AT accounts
			atAccount.setConfirmedBalance(Asset.QORT, atAccount.getConfirmedBalance(Asset.QORT).add(atState.getFees()));
		}

		// Delete ATStateData for this height
		atRepository.deleteATStates(this.blockData.getHeight());
	}

	protected void decreaseAccountLevels() throws DataException {
		// TODO !
	}

	protected void distributeByAccountLevel(BigDecimal totalAmount) throws DataException {
		List<ShareByLevel> sharesByLevel = BlockChain.getInstance().getBlockSharesByLevel();
		List<ExpandedAccount> expandedAccounts = this.getExpandedAccounts();

		// Distribute amount across bins
		BigDecimal sharedAmount = BigDecimal.ZERO;
		for (int s = 0; s < sharesByLevel.size(); ++s) {
			final int binIndex = s;

			BigDecimal binAmount = sharesByLevel.get(binIndex).share.multiply(totalAmount).setScale(8, RoundingMode.DOWN);
			LOGGER.trace(() -> String.format("Bin %d share of %s: %s", binIndex, totalAmount.toPlainString(), binAmount.toPlainString()));

			// Spread across all accounts in bin
			List<ExpandedAccount> binnedAccounts = expandedAccounts.stream().filter(accountInfo -> !accountInfo.isForgerFounder && accountInfo.shareBin == binIndex).collect(Collectors.toList());
			if (binnedAccounts.isEmpty())
				continue;

			BigDecimal binSize = BigDecimal.valueOf(binnedAccounts.size());
			BigDecimal accountAmount = binAmount.divide(binSize, RoundingMode.DOWN);

			for (int a = 0; a < binnedAccounts.size(); ++a) {
				ExpandedAccount expandedAccount = binnedAccounts.get(a);
				expandedAccount.distribute(accountAmount);
				sharedAmount = sharedAmount.add(accountAmount);
			}
		}

		// Distribute share across legacy QORA holders
		BigDecimal qoraHoldersAmount = BlockChain.getInstance().getQoraHoldersShare().multiply(totalAmount).setScale(8, RoundingMode.DOWN);
		LOGGER.trace(() -> String.format("Legacy QORA holders share of %s: %s", totalAmount.toPlainString(), qoraHoldersAmount.toPlainString()));

		List<ExpandedAccount> qoraHolderAccounts = new ArrayList<>();
		BigDecimal totalQoraHeld = BigDecimal.ZERO;
		for (int i = 0; i < expandedAccounts.size(); ++i) {
			ExpandedAccount expandedAccount = expandedAccounts.get(i);
			if (expandedAccount.forgerQoraAmount == null)
				continue;

			qoraHolderAccounts.add(expandedAccount);
			totalQoraHeld = totalQoraHeld.add(expandedAccount.forgerQoraAmount);
		}

		final BigDecimal finalTotalQoraHeld = totalQoraHeld;
		LOGGER.trace(() -> String.format("Total legacy QORA held: %s", finalTotalQoraHeld.toPlainString()));

		for (int h = 0; h < qoraHolderAccounts.size(); ++h) {
			ExpandedAccount expandedAccount = qoraHolderAccounts.get(h);
			final BigDecimal holderAmount = qoraHoldersAmount.multiply(totalQoraHeld).divide(expandedAccount.forgerQoraAmount, RoundingMode.DOWN);
			LOGGER.trace(() -> String.format("Forger account %s has %s / %s QORA so share: %s",
					expandedAccount.forgerAccount.getAddress(), expandedAccount.forgerQoraAmount, finalTotalQoraHeld, holderAmount.toPlainString()));

			expandedAccount.distribute(holderAmount);
			sharedAmount = sharedAmount.add(holderAmount);
		}

		// Spread remainder across founder accounts
		BigDecimal foundersAmount = totalAmount.subtract(sharedAmount);
		LOGGER.debug(String.format("Shared %s of %s, remaining %s to founders", sharedAmount.toPlainString(), totalAmount.toPlainString(), foundersAmount.toPlainString()));

		List<ExpandedAccount> founderAccounts = expandedAccounts.stream().filter(accountInfo -> accountInfo.isForgerFounder).collect(Collectors.toList());
		if (founderAccounts.isEmpty())
			return;

		BigDecimal foundersCount = BigDecimal.valueOf(founderAccounts.size());
		BigDecimal accountAmount = foundersAmount.divide(foundersCount, RoundingMode.DOWN);

		for (int a = 0; a < founderAccounts.size(); ++a) {
			ExpandedAccount expandedAccount = founderAccounts.get(a);
			expandedAccount.distribute(accountAmount);
			sharedAmount = sharedAmount.add(accountAmount);
		}
	}

}
