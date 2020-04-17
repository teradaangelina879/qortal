package org.qortal.at;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.List;

import org.ciyam.at.MachineState;
import org.ciyam.at.Timestamp;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.repository.ATRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.AtTransaction;

public class AT {

	// Properties
	private Repository repository;
	private ATData atData;
	private ATStateData atStateData;

	// Constructors

	public AT(Repository repository, ATData atData, ATStateData atStateData) {
		this.repository = repository;
		this.atData = atData;
		this.atStateData = atStateData;
	}

	public AT(Repository repository, ATData atData) {
		this(repository, atData, null);
	}

	/** Constructs AT-handling object when deploying AT */
	public AT(Repository repository, DeployAtTransactionData deployATTransactionData) throws DataException {
		this.repository = repository;

		String atAddress = deployATTransactionData.getAtAddress();
		int height = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		byte[] creatorPublicKey = deployATTransactionData.getCreatorPublicKey();
		long creation = deployATTransactionData.getTimestamp();

		byte[] creationBytes = deployATTransactionData.getCreationBytes();
		long assetId = deployATTransactionData.getAssetId();
		short version = (short) ((creationBytes[0] & 0xff) | (creationBytes[1] << 8)); // Little-endian

		if (version >= 2) {
			// Just enough AT data to allow API to query initial balances, etc.
			ATData skeletonAtData = new ATData(atAddress, creatorPublicKey, creation, assetId);

			long blockTimestamp = Timestamp.toLong(height, 0);
			QortalATAPI api = new QortalATAPI(repository, skeletonAtData, blockTimestamp);
			QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();

			MachineState machineState = new MachineState(api, loggerFactory, deployATTransactionData.getCreationBytes());

			byte[] codeHash = Crypto.digest(machineState.getCodeBytes());

			this.atData = new ATData(atAddress, creatorPublicKey, creation, machineState.version, assetId, machineState.getCodeBytes(), codeHash,
					machineState.isSleeping(), machineState.getSleepUntilHeight(), machineState.isFinished(), machineState.hadFatalError(),
					machineState.isFrozen(), machineState.getFrozenBalance());

			byte[] stateData = machineState.toBytes();
			byte[] stateHash = Crypto.digest(stateData);

			this.atStateData = new ATStateData(atAddress, height, creation, stateData, stateHash, BigDecimal.ZERO.setScale(8), true);
		} else {
			// Legacy v1 AT
			// We would deploy these in 'dead' state as they will never be run on Qortal
			// but this breaks import from Qora1 so something else will have to mark them dead at hard-fork

			// Extract code bytes length
			ByteBuffer byteBuffer = ByteBuffer.wrap(deployATTransactionData.getCreationBytes());

			// v1 AT header is: version, reserved, code-pages, data-pages, call-stack-pages, user-stack-pages (all shorts)

			// Number of code pages
			short numCodePages = byteBuffer.get(2 + 2);

			// Skip header and also "minimum activation amount" (long)
			byteBuffer.position(6 * 2 + 8);

			int codeLen = 0;

			// Extract actual code length, stored in minimal-size form (byte, short or int)
			if (numCodePages * 256 < 257) {
				codeLen = byteBuffer.get() & 0xff;
			} else if (numCodePages * 256 < Short.MAX_VALUE + 1) {
				codeLen = byteBuffer.getShort() & 0xffff;
			} else if (numCodePages * 256 <= Integer.MAX_VALUE) {
				codeLen = byteBuffer.getInt();
			}

			// Extract code bytes
			byte[] codeBytes = new byte[codeLen];
			byteBuffer.get(codeBytes);

			// Create AT
			boolean isSleeping = false;
			Integer sleepUntilHeight = null;
			boolean isFinished = false;
			boolean hadFatalError = false;
			boolean isFrozen = false;
			Long frozenBalance = null;
			byte[] codeHash = Crypto.digest(codeBytes);

			this.atData = new ATData(atAddress, creatorPublicKey, creation, version, Asset.QORT, codeBytes, codeHash,
					isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, frozenBalance);

			this.atStateData = new ATStateData(atAddress, height, creation, null, null, BigDecimal.ZERO.setScale(8), true);
		}
	}

	// Getters / setters

	public ATStateData getATStateData() {
		return this.atStateData;
	}

	// Processing

	public void deploy() throws DataException {
		ATRepository atRepository = this.repository.getATRepository();
		atRepository.save(this.atData);

		// For version 2+ we also store initial AT state data
		if (this.atData.getVersion() >= 2)
			atRepository.save(this.atStateData);
	}

	public void undeploy() throws DataException {
		// AT states deleted implicitly by repository
		this.repository.getATRepository().delete(this.atData.getATAddress());
	}

	public List<AtTransaction> run(int blockHeight, long blockTimestamp) throws DataException {
		String atAddress = this.atData.getATAddress();

		QortalATAPI api = new QortalATAPI(repository, this.atData, blockTimestamp);
		QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();

		byte[] codeBytes = this.atData.getCodeBytes();

		// Fetch latest ATStateData for this AT
		ATStateData latestAtStateData = this.repository.getATRepository().getLatestATState(atAddress);

		// There should be at least initial deployment AT state data
		if (latestAtStateData == null)
			throw new IllegalStateException("No previous AT state data found");

		// [Re]create AT machine state using AT state data or from scratch as applicable
		MachineState state = MachineState.fromBytes(api, loggerFactory, latestAtStateData.getStateData(), codeBytes);
		state.execute();

		long creation = this.atData.getCreation();
		byte[] stateData = state.toBytes();
		byte[] stateHash = Crypto.digest(stateData);
		BigDecimal atFees = api.calcFinalFees(state);

		this.atStateData = new ATStateData(atAddress, blockHeight, creation, stateData, stateHash, atFees, false);

		return api.getTransactions();
	}

	public void update(int blockHeight, long blockTimestamp) throws DataException {
		// [Re]create AT machine state using AT state data or from scratch as applicable
		QortalATAPI api = new QortalATAPI(repository, this.atData, blockTimestamp);
		QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();

		byte[] codeBytes = this.atData.getCodeBytes();
		MachineState state = MachineState.fromBytes(api, loggerFactory, this.atStateData.getStateData(), codeBytes);

		// Save latest AT state data
		this.repository.getATRepository().save(this.atStateData);

		// Update AT info in repository too
		this.atData.setIsSleeping(state.isSleeping());
		this.atData.setSleepUntilHeight(state.getSleepUntilHeight());
		this.atData.setIsFinished(state.isFinished());
		this.atData.setHadFatalError(state.hadFatalError());
		this.atData.setIsFrozen(state.isFrozen());
		Long frozenBalance = state.getFrozenBalance();
		this.atData.setFrozenBalance(frozenBalance != null ? BigDecimal.valueOf(frozenBalance, 8) : null);
		this.repository.getATRepository().save(this.atData);
	}

	public void revert(int blockHeight, long blockTimestamp) throws DataException {
		String atAddress = this.atData.getATAddress();

		// Delete old AT state data from repository
		this.repository.getATRepository().delete(atAddress, blockHeight);

		if (this.atStateData.isInitial())
			return;

		// Load previous state data
		ATStateData previousStateData = this.repository.getATRepository().getLatestATState(atAddress);
		if (previousStateData == null)
			throw new DataException("Can't find previous AT state data for " + atAddress);

		// [Re]create AT machine state using AT state data or from scratch as applicable
		QortalATAPI api = new QortalATAPI(repository, this.atData, blockTimestamp);
		QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();

		byte[] codeBytes = this.atData.getCodeBytes();
		MachineState state = MachineState.fromBytes(api, loggerFactory, previousStateData.getStateData(), codeBytes);

		// Update AT info in repository
		this.atData.setIsSleeping(state.isSleeping());
		this.atData.setSleepUntilHeight(state.getSleepUntilHeight());
		this.atData.setIsFinished(state.isFinished());
		this.atData.setHadFatalError(state.hadFatalError());
		this.atData.setIsFrozen(state.isFrozen());
		Long frozenBalance = state.getFrozenBalance();
		this.atData.setFrozenBalance(frozenBalance != null ? BigDecimal.valueOf(frozenBalance, 8) : null);
		this.repository.getATRepository().save(this.atData);
	}

}
