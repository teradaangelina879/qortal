package org.qora.block;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.transform.stream.StreamSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.exceptions.XMLMarshalException;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.qora.controller.Controller;
import org.qora.data.block.BlockData;
import org.qora.network.Network;
import org.qora.repository.BlockRepository;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.settings.Settings;
import org.qora.utils.NTP;
import org.qora.utils.StringLongMapXmlAdapter;

/**
 * Class representing the blockchain as a whole.
 *
 */
// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class BlockChain {

	private static final Logger LOGGER = LogManager.getLogger(BlockChain.class);

	private static BlockChain instance = null;

	// Properties

	private boolean isTestChain = false;
	/** Transaction expiry period, starting from transaction's timestamp, in milliseconds. */
	private long transactionExpiryPeriod;

	private BigDecimal unitFee;
	private BigDecimal maxBytesPerUnitFee;
	private BigDecimal minFeePerByte;

	/** Number of blocks between recalculating block's generating balance. */
	private int blockDifficultyInterval;
	/** Maximum acceptable timestamp disagreement offset in milliseconds. */
	private long blockTimestampMargin;
	/** Maximum block size, in bytes. */
	private int maxBlockSize;

	/** Whether transactions with txGroupId of NO_GROUP are allowed */
	private boolean requireGroupForApproval;

	private GenesisBlock.GenesisInfo genesisInfo;

	public enum FeatureTrigger {
		messageHeight,
		atHeight,
		assetsTimestamp,
		votingTimestamp,
		arbitraryTimestamp,
		powfixTimestamp,
		v2Timestamp,
		newAssetPricingTimestamp,
		groupApprovalTimestamp;
	}

	/** Map of which blockchain features are enabled when (height/timestamp) */
	@XmlJavaTypeAdapter(StringLongMapXmlAdapter.class)
	private Map<String, Long> featureTriggers;

	/** Whether to use legacy, broken RIPEMD160 implementation when converting public keys to addresses. */
	private boolean useBrokenMD160ForAddresses = false;

	/** Whether only one registered name is allowed per account. */
	private boolean oneNamePerAccount = false;

	/** Block rewards by block height */
	public static class RewardByHeight {
		public int height;
		public BigDecimal reward;
	}
	List<RewardByHeight> rewardsByHeight;

	/** Share of block reward/fees by account level */
	public static class ShareByLevel {
		public List<Integer> levels;
		public BigDecimal share;
	}
	List<ShareByLevel> sharesByLevel;

	/** Block times by block height */
	public static class BlockTimingByHeight {
		public int height;
		public long target; // ms
		public long deviation; // ms
		public double power;
	}
	List<BlockTimingByHeight> blockTimingsByHeight;

	/** Forging right tiers */
	public static class ForgingTier {
		/** Minimum number of blocks forged before account can enable minting on other accounts. */
		public int minBlocks;
		/** Maximum number of other accounts that can be enabled. */
		public int maxSubAccounts;
	}
	List<ForgingTier> forgingTiers;

	private int maxProxyRelationships;

	/** Minimum time to retain online account signatures (ms) for block validity checks. */
	private long onlineAccountSignaturesMinLifetime;
	/** Maximum time to retain online account signatures (ms) for block validity checks, to allow for clock variance. */
	private long onlineAccountSignaturesMaxLifetime;


	// Constructors, etc.

	private BlockChain() {
	}

	public static BlockChain getInstance() {
		if (instance == null)
			// This will call BlockChain.fromJSON in turn
			Settings.getInstance(); // synchronized

		return instance;
	}

	/** Use blockchain config read from <tt>path</tt> + <tt>filename</tt>, or use resources-based default if <tt>filename</tt> is <tt>null</tt>. */
	public static void fileInstance(String path, String filename) {
		JAXBContext jc;
		Unmarshaller unmarshaller;

		try {
			// Create JAXB context aware of Settings
			jc = JAXBContextFactory.createContext(new Class[] {
				BlockChain.class, GenesisBlock.GenesisInfo.class
			}, null);

			// Create unmarshaller
			unmarshaller = jc.createUnmarshaller();

			// Set the unmarshaller media type to JSON
			unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");

			// Tell unmarshaller that there's no JSON root element in the JSON input
			unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);

		} catch (JAXBException e) {
			String message = "Failed to setup unmarshaller to process blockchain config file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}

		BlockChain blockchain = null;
		StreamSource jsonSource;

		if (filename != null) {
			LOGGER.info("Using blockchain config file: " + path + filename);

			File jsonFile = new File(path + filename);

			if (!jsonFile.exists()) {
				String message = "Blockchain config file not found: " + path + filename;
				LOGGER.error(message);
				throw new RuntimeException(message, new FileNotFoundException(message));
			}

			jsonSource = new StreamSource(jsonFile);
		} else {
			LOGGER.info("Using default, resources-based blockchain config");

			ClassLoader classLoader = BlockChain.class.getClassLoader();
			InputStream in = classLoader.getResourceAsStream("blockchain.json");
			jsonSource = new StreamSource(in);
		}

		try  {
			// Attempt to unmarshal JSON stream to BlockChain config
			blockchain = unmarshaller.unmarshal(jsonSource, BlockChain.class).getValue();
		} catch (UnmarshalException e) {
			Throwable linkedException = e.getLinkedException();
			if (linkedException instanceof XMLMarshalException) {
				String message = ((XMLMarshalException) linkedException).getInternalException().getLocalizedMessage();
				LOGGER.error(message);
				throw new RuntimeException(message, e);
			}

			String message = "Failed to parse blockchain config file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		} catch (JAXBException e) {
			String message = "Unexpected JAXB issue while processing blockchain config file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}

		// Validate config
		blockchain.validateConfig();

		// Minor fix-up
		blockchain.maxBytesPerUnitFee.setScale(8);
		blockchain.unitFee.setScale(8);
		blockchain.minFeePerByte = blockchain.unitFee.divide(blockchain.maxBytesPerUnitFee, MathContext.DECIMAL32);

		// Successfully read config now in effect
		instance = blockchain;

		// Pass genesis info to GenesisBlock
		GenesisBlock.newInstance(blockchain.genesisInfo);
	}

	// Getters / setters

	public boolean isTestChain() {
		return this.isTestChain;
	}

	public BigDecimal getUnitFee() {
		return this.unitFee;
	}

	public BigDecimal getMaxBytesPerUnitFee() {
		return this.maxBytesPerUnitFee;
	}

	public BigDecimal getMinFeePerByte() {
		return this.minFeePerByte;
	}

	public long getTransactionExpiryPeriod() {
		return this.transactionExpiryPeriod;
	}

	public int getBlockDifficultyInterval() {
		return this.blockDifficultyInterval;
	}

	public long getBlockTimestampMargin() {
		return this.blockTimestampMargin;
	}

	public int getMaxBlockSize() {
		return this.maxBlockSize;
	}

	/** Returns true if approval-needing transaction types require a txGroupId other than NO_GROUP. */
	public boolean getRequireGroupForApproval() {
		return this.requireGroupForApproval;
	}

	public boolean getUseBrokenMD160ForAddresses() {
		return this.useBrokenMD160ForAddresses;
	}

	public boolean oneNamePerAccount() {
		return this.oneNamePerAccount;
	}

	public List<RewardByHeight> getBlockRewardsByHeight() {
		return this.rewardsByHeight;
	}

	public List<ShareByLevel> getBlockSharesByLevel() {
		return this.sharesByLevel;
	}

	public List<ForgingTier> getForgingTiers() {
		return this.forgingTiers;
	}

	public int getMaxProxyRelationships() {
		return this.maxProxyRelationships;
	}

	public long getOnlineAccountSignaturesMinLifetime() {
		return this.onlineAccountSignaturesMinLifetime;
	}

	public long getOnlineAccountSignaturesMaxLifetime() {
		return this.onlineAccountSignaturesMaxLifetime;
	}

	// Convenience methods for specific blockchain feature triggers

	public long getMessageReleaseHeight() {
		return featureTriggers.get("messageHeight");
	}

	public long getATReleaseHeight() {
		return featureTriggers.get("atHeight");
	}

	public long getPowFixReleaseTimestamp() {
		return featureTriggers.get("powfixTimestamp");
	}

	public long getAssetsReleaseTimestamp() {
		return featureTriggers.get("assetsTimestamp");
	}

	public long getVotingReleaseTimestamp() {
		return featureTriggers.get("votingTimestamp");
	}

	public long getArbitraryReleaseTimestamp() {
		return featureTriggers.get("arbitraryTimestamp");
	}

	public long getQoraV2Timestamp() {
		return featureTriggers.get("v2Timestamp");
	}

	public long getNewAssetPricingTimestamp() {
		return featureTriggers.get("newAssetPricingTimestamp");
	}

	public long getGroupApprovalTimestamp() {
		return featureTriggers.get("groupApprovalTimestamp");
	}

	// More complex getters for aspects that change by height or timestamp

	public BigDecimal getRewardAtHeight(int ourHeight) {
		// Scan through for reward at our height
		for (int i = rewardsByHeight.size() - 1; i >= 0; --i)
			if (rewardsByHeight.get(i).height <= ourHeight)
				return rewardsByHeight.get(i).reward;

		return null;
	}

	public BlockTimingByHeight getBlockTimingByHeight(int ourHeight) {
		for (int i = blockTimingsByHeight.size() - 1; i >= 0; --i)
			if (blockTimingsByHeight.get(i).height <= ourHeight)
				return blockTimingsByHeight.get(i);

		throw new IllegalStateException(String.format("No block timing info available for height %d", ourHeight));
	}

	/** Validate blockchain config read from JSON */
	private void validateConfig() {
		if (this.genesisInfo == null)
			Settings.throwValidationError("No \"genesisInfo\" entry found in blockchain config");

		if (this.rewardsByHeight == null)
			Settings.throwValidationError("No \"rewardsByHeight\" entry found in blockchain config");

		if (this.sharesByLevel == null)
			Settings.throwValidationError("No \"sharesByLevel\" entry found in blockchain config");

		if (this.blockTimingsByHeight == null)
			Settings.throwValidationError("No \"blockTimingsByHeight\" entry found in blockchain config");

		if (this.blockTimestampMargin <= 0)
			Settings.throwValidationError("Invalid \"blockTimestampMargin\" in blockchain config");

		if (this.transactionExpiryPeriod <= 0)
			Settings.throwValidationError("Invalid \"transactionExpiryPeriod\" in blockchain config");

		if (this.maxBlockSize <= 0)
			Settings.throwValidationError("Invalid \"maxBlockSize\" in blockchain config");

		if (this.featureTriggers == null)
			Settings.throwValidationError("No \"featureTriggers\" entry found in blockchain config");

		// Check all featureTriggers are present
		for (FeatureTrigger featureTrigger : FeatureTrigger.values())
			if (!this.featureTriggers.containsKey(featureTrigger.name()))
				Settings.throwValidationError(String.format("Missing feature trigger \"%s\" in blockchain config", featureTrigger.name()));
	}

	/**
	 * Some sort start-up/initialization/checking method.
	 * 
	 * @throws SQLException
	 */
	public static void validate() throws DataException {
		// Check first block is Genesis Block
		if (!isGenesisBlockValid())
			rebuildBlockchain();

		// TODO: walk through blocks
		try (final Repository repository = RepositoryManager.getRepository()) {
			Block parentBlock = GenesisBlock.getInstance(repository);
			BlockData parentBlockData = parentBlock.getBlockData();

			while (true) {
				BlockData childBlockData = parentBlock.getChild();
				if (childBlockData == null)
					break;

				if (!Arrays.equals(childBlockData.getReference(), parentBlock.getSignature())) {
					LOGGER.error(String.format("Block %d's reference does not match block %d's signature", childBlockData.getHeight(), parentBlockData.getHeight()));
					rebuildBlockchain();
					return;
				}

				parentBlock = new Block(repository, childBlockData);
				parentBlockData = childBlockData;
			}
		}
	}

	private static boolean isGenesisBlockValid() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockRepository blockRepository = repository.getBlockRepository();

			int blockchainHeight = blockRepository.getBlockchainHeight();
			if (blockchainHeight < 1)
				return false;

			BlockData blockData = blockRepository.fromHeight(1);
			if (blockData == null)
				return false;

			return GenesisBlock.isGenesisBlock(blockData);
		}
	}

	private static void rebuildBlockchain() throws DataException {
		// (Re)build repository
		try (final Repository repository = RepositoryManager.getRepository()) {
			repository.rebuild();

			GenesisBlock genesisBlock = GenesisBlock.getInstance(repository);

			// Add Genesis Block to blockchain
			genesisBlock.process();

			repository.saveChanges();

			// Give Network a change to install initial seed peers
			Network.installInitialPeers(repository);
		}
	}

	public static boolean orphan(int targetHeight) throws DataException {
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		if (!blockchainLock.tryLock())
			return false;

		try {
			try (final Repository repository = RepositoryManager.getRepository()) {
				for (int height = repository.getBlockRepository().getBlockchainHeight(); height > targetHeight; --height) {
					LOGGER.info(String.format("Forcably orphaning block %d", height));

					BlockData blockData = repository.getBlockRepository().fromHeight(height);
					Block block = new Block(repository, blockData);
					block.orphan();
					repository.saveChanges();
				}

				BlockData lastBlockData = repository.getBlockRepository().getLastBlock();
				Controller.getInstance().setChainTip(lastBlockData);

				return true;
			}
		} finally {
			blockchainLock.unlock();
		}
	}

	public static void trimOldOnlineAccountsSignatures() {
		final Long now = NTP.getTime();
		if (now == null)
			return;

		try (final Repository repository = RepositoryManager.tryRepository()) {
			if (repository == null)
				return;

			int numBlocksTrimmed = repository.getBlockRepository().trimOldOnlineAccountsSignatures(now - BlockChain.getInstance().getOnlineAccountSignaturesMaxLifetime());

			if (numBlocksTrimmed > 0)
				LOGGER.debug(String.format("Trimmed old online accounts signatures from %d block%s", numBlocksTrimmed, (numBlocksTrimmed != 1 ? "s" : "")));

			repository.saveChanges();
		} catch (DataException e) {
			LOGGER.warn("Repository issue trying to trim old online accounts signatures: " + e.getMessage());
			return;
		}
	}

}
