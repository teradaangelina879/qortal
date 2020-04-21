package org.qortal.block;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.account.GenesisAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.crypto.Crypto;
import org.qortal.data.asset.AssetData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.IssueAssetTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ApprovalStatus;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

public class GenesisBlock extends Block {

	private static final Logger LOGGER = LogManager.getLogger(GenesisBlock.class);

	private static final byte[] GENESIS_REFERENCE = new byte[] {
		1, 1, 1, 1, 1, 1, 1, 1
	}; // NOTE: Neither 64 nor 128 bytes!

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class GenesisInfo {
		public int version = 1;
		public long timestamp;

		public TransactionData[] transactions;

		public GenesisInfo() {
		}
	}

	// Properties
	private static BlockData genesisBlockData;
	private static List<TransactionData> transactionsData;
	private static List<AssetData> initialAssets;

	// Constructors

	private GenesisBlock(Repository repository, BlockData blockData, List<TransactionData> transactions) throws DataException {
		super(repository, blockData, transactions, Collections.emptyList());
	}

	public static GenesisBlock getInstance(Repository repository) throws DataException {
		return new GenesisBlock(repository, genesisBlockData, transactionsData);
	}

	// Construction from JSON

	/** Construct block data from blockchain config */
	public static void newInstance(GenesisInfo info) {
		// Should be safe to make this call as BlockChain's instance is set
		// so we won't be blocked trying to re-enter synchronized Settings.getInstance()
		BlockChain blockchain = BlockChain.getInstance();

		// Timestamp of zero means "now" but only valid for test nets!
		if (info.timestamp == 0) {
			if (!blockchain.isTestChain()) {
				LOGGER.error("Genesis timestamp of zero (i.e. now) not valid for non-test blockchain configs");
				throw new RuntimeException("Genesis timestamp of zero (i.e. now) not valid for non-test blockchain configs");
			}

			// This will only take effect if there is no current genesis block in blockchain
			info.timestamp = System.currentTimeMillis();
		}

		transactionsData = new ArrayList<>(Arrays.asList(info.transactions));

		// Add default values to transactions
		transactionsData.stream().forEach(transactionData -> {
			if (transactionData.getFee() == null)
				transactionData.setFee(BigDecimal.ZERO.setScale(8));

			if (transactionData.getCreatorPublicKey() == null)
				transactionData.setCreatorPublicKey(GenesisAccount.PUBLIC_KEY);

			if (transactionData.getTimestamp() == 0)
				transactionData.setTimestamp(info.timestamp);
		});

		// For version 1, extract any ISSUE_ASSET transactions into initialAssets and only allow GENESIS transactions
		if (info.version == 1) {
			List<TransactionData> issueAssetTransactions = transactionsData.stream()
					.filter(transactionData -> transactionData.getType() == TransactionType.ISSUE_ASSET).collect(Collectors.toList());
			transactionsData.removeAll(issueAssetTransactions);

			// There should be only GENESIS transactions left;
			if (transactionsData.stream().anyMatch(transactionData -> transactionData.getType() != TransactionType.GENESIS)) {
				LOGGER.error("Version 1 genesis block only allowed to contain GENESIS transctions (after issue-asset processing)");
				throw new RuntimeException("Version 1 genesis block only allowed to contain GENESIS transctions (after issue-asset processing)");
			}

			// Convert ISSUE_ASSET transactions into initial assets
			initialAssets = issueAssetTransactions.stream().map(transactionData -> {
				IssueAssetTransactionData issueAssetTransactionData = (IssueAssetTransactionData) transactionData;

				return new AssetData(issueAssetTransactionData.getOwner(), issueAssetTransactionData.getAssetName(), issueAssetTransactionData.getDescription(),
						issueAssetTransactionData.getQuantity(), issueAssetTransactionData.getIsDivisible(), "", false, Group.NO_GROUP, issueAssetTransactionData.getReference());
			}).collect(Collectors.toList());
		}

		byte[] reference = GENESIS_REFERENCE;
		int transactionCount = transactionsData.size();
		BigDecimal totalFees = BigDecimal.ZERO.setScale(8);
		byte[] minterPublicKey = GenesisAccount.PUBLIC_KEY;
		byte[] bytesForSignature = getBytesForMinterSignature(info.timestamp, reference, minterPublicKey);
		byte[] minterSignature = calcGenesisMinterSignature(bytesForSignature);
		byte[] transactionsSignature = calcGenesisTransactionsSignature();
		int height = 1;
		int atCount = 0;
		BigDecimal atFees = BigDecimal.ZERO.setScale(8);

		genesisBlockData = new BlockData(info.version, reference, transactionCount, totalFees, transactionsSignature, height, info.timestamp,
				minterPublicKey, minterSignature, atCount, atFees);
	}

	// More information

	public static boolean isGenesisBlock(BlockData blockData) {
		if (blockData.getHeight() != 1)
			return false;

		byte[] signature = calcSignature(blockData);

		// Validate block minter's signature (first 64 bytes of block signature)
		if (!Arrays.equals(signature, 0, 64, genesisBlockData.getMinterSignature(), 0, 64))
			return false;

		// Validate transactions signature (last 64 bytes of block signature)
		if (!Arrays.equals(signature, 64, 128, genesisBlockData.getTransactionsSignature(), 0, 64))
			return false;

		return true;
	}

	public List<AssetData> getInitialAssets() {
		return Collections.unmodifiableList(initialAssets);
	}

	// Processing

	@Override
	public boolean addTransaction(TransactionData transactionData) {
		// The genesis block has a fixed set of transactions so always refuse.
		return false;
	}

	/**
	 * Refuse to calculate genesis block's minter signature!
	 * <p>
	 * This is not possible as there is no private key for the genesis account and so no way to sign data.
	 * <p>
	 * <b>Always throws IllegalStateException.</b>
	 * 
	 * @throws IllegalStateException
	 */
	@Override
	public void calcMinterSignature() {
		throw new IllegalStateException("There is no private key for genesis account");
	}

	/**
	 * Refuse to calculate genesis block's transactions signature!
	 * <p>
	 * This is not possible as there is no private key for the genesis account and so no way to sign data.
	 * <p>
	 * <b>Always throws IllegalStateException.</b>
	 * 
	 * @throws IllegalStateException
	 */
	@Override
	public void calcTransactionsSignature() {
		throw new IllegalStateException("There is no private key for genesis account");
	}

	/**
	 * Generate genesis block minter signature.
	 * <p>
	 * This is handled differently as there is no private key for the genesis account and so no way to sign data.
	 * 
	 * @return byte[]
	 */
	private static byte[] calcGenesisMinterSignature(byte[] bytes) {
		return Crypto.dupDigest(bytes);
	}

	private static byte[] getBytesForMinterSignature(long timestamp, byte[] reference, byte[] minterPublicKey) {
		try {
			// Passing expected size to ByteArrayOutputStream avoids reallocation when adding more bytes than default 32.
			// See below for explanation of some of the values used to calculated expected size.
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 + 64 + 8 + 32);

			/*
			 * NOTE: Historic code had genesis block using Longs.toByteArray(version) compared to standard block's Ints.toByteArray. The subsequent
			 * Bytes.ensureCapacity(versionBytes, 0, 4) did not truncate versionBytes back to 4 bytes either. This means 8 bytes were used even though
			 * VERSION_LENGTH is set to 4. Correcting this historic bug will break genesis block signatures!
			 */
			// For Qortal, we use genesis timestamp instead
			bytes.write(Longs.toByteArray(timestamp));

			/*
			 * NOTE: Historic code had the reference expanded to only 64 bytes whereas standard block references are 128 bytes. Correcting this historic bug
			 * will break genesis block signatures!
			 */
			bytes.write(Bytes.ensureCapacity(reference, 64, 0));

			// NOTE: Genesis account's public key is only 8 bytes, not the usual 32, so we have to pad.
			bytes.write(Bytes.ensureCapacity(minterPublicKey, 32, 0));

			return bytes.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static byte[] calcGenesisTransactionsSignature() {
		// transaction index (int), transaction type (int), creator pubkey (32): so 40 bytes each
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(transactionsData.size() * (4 + 4 + 32));

		try {
			for (int ti = 0; ti < transactionsData.size(); ++ti) {
				bytes.write(Ints.toByteArray(ti));

				bytes.write(Ints.toByteArray(transactionsData.get(ti).getType().value));

				bytes.write(transactionsData.get(ti).getCreatorPublicKey());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return Crypto.dupDigest(bytes.toByteArray());
	}

	/** Convenience method for calculating genesis block signatures from block data */
	private static byte[] calcSignature(BlockData blockData) {
		byte[] bytes = getBytesForMinterSignature(blockData.getTimestamp(), blockData.getReference(), blockData.getMinterPublicKey());
		return Bytes.concat(calcGenesisMinterSignature(bytes), calcGenesisTransactionsSignature());
	}

	@Override
	public boolean isSignatureValid() {
		byte[] signature = calcSignature(this.blockData);

		// Validate block minter's signature (first 64 bytes of block signature)
		if (!Arrays.equals(signature, 0, 64, this.blockData.getMinterSignature(), 0, 64))
			return false;

		// Validate transactions signature (last 64 bytes of block signature)
		if (!Arrays.equals(signature, 64, 128, this.blockData.getTransactionsSignature(), 0, 64))
			return false;

		return true;
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Check there is no other block in DB
		if (this.repository.getBlockRepository().getBlockchainHeight() != 0)
			return ValidationResult.BLOCKCHAIN_NOT_EMPTY;

		// Validate transactions
		for (Transaction transaction : this.getTransactions())
			if (transaction.isValid() != Transaction.ValidationResult.OK)
				return ValidationResult.TRANSACTION_INVALID;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		LOGGER.info(String.format("Using genesis block timestamp of %d", this.blockData.getTimestamp()));

		// If we're a version 1 genesis block, create assets now
		if (this.blockData.getVersion() == 1)
			for (AssetData assetData : initialAssets)
				repository.getAssetRepository().save(assetData);

		/*
		 * Some transactions will be missing references and signatures,
		 * so we generate them by trial-processing transactions and using
		 * account's last-reference to fill in the gaps for reference,
		 * and a duplicated SHA256 digest for signature
		 */
		this.repository.setSavepoint();
		try {
			for (Transaction transaction : this.getTransactions()) {
				TransactionData transactionData = transaction.getTransactionData();
				Account creator = new PublicKeyAccount(this.repository, transactionData.getCreatorPublicKey());

				// Missing reference?
				if (transactionData.getReference() == null)
					transactionData.setReference(creator.getLastReference());

				// Missing signature?
				if (transactionData.getSignature() == null) {
					byte[] digest = Crypto.digest(TransactionTransformer.toBytesForSigning(transactionData));
					byte[] signature = Bytes.concat(digest, digest);

					transactionData.setSignature(signature);
				}

				// Missing approval status (not used in V1)
				transactionData.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);

				// Ask transaction to update references, etc.
				transaction.processReferencesAndFees();

				creator.setLastReference(transactionData.getSignature());
			}
		} catch (TransformationException e) {
			throw new RuntimeException("Can't process genesis block transaction", e);
		} finally {
			this.repository.rollbackToSavepoint();
		}

		// Save transactions into repository ready for processing
		for (Transaction transaction : this.getTransactions())
			this.repository.getTransactionRepository().save(transaction.getTransactionData());

		// No ATs in genesis block
		this.ourAtStates = Collections.emptyList();
		this.ourAtFees = BigDecimal.ZERO.setScale(8);

		super.process();
	}

}
