package org.qortal.at;

import java.util.List;

import org.ciyam.at.MachineState;
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
		long assetId = deployATTransactionData.getAssetId();

		MachineState machineState = new MachineState(deployATTransactionData.getCreationBytes());

		this.atData = new ATData(atAddress, creatorPublicKey, creation, machineState.version, assetId, machineState.getCodeBytes(),
				machineState.getIsSleeping(), machineState.getSleepUntilHeight(), machineState.getIsFinished(), machineState.getHadFatalError(),
				machineState.getIsFrozen(), machineState.getFrozenBalance());

		byte[] stateData = machineState.toBytes();
		byte[] stateHash = Crypto.digest(stateData);

		this.atStateData = new ATStateData(atAddress, height, creation, stateData, stateHash, 0L);
	}

	// Getters / setters

	public ATStateData getATStateData() {
		return this.atStateData;
	}

	// Processing

	public void deploy() throws DataException {
		ATRepository atRepository = this.repository.getATRepository();
		atRepository.save(this.atData);

		atRepository.save(this.atStateData);
	}

	public void undeploy() throws DataException {
		// AT states deleted implicitly by repository
		this.repository.getATRepository().delete(this.atData.getATAddress());
	}

	public List<AtTransaction> run(long blockTimestamp) throws DataException {
		String atAddress = this.atData.getATAddress();

		QortalATAPI api = new QortalATAPI(repository, this.atData, blockTimestamp);
		QortalATLogger logger = new QortalATLogger();

		byte[] codeBytes = this.atData.getCodeBytes();

		// Fetch latest ATStateData for this AT (if any)
		ATStateData latestAtStateData = this.repository.getATRepository().getLatestATState(atAddress);

		// There should be at least initial AT state data
		if (latestAtStateData == null)
			throw new IllegalStateException("No initial AT state data found");

		// [Re]create AT machine state using AT state data or from scratch as applicable
		MachineState state = MachineState.fromBytes(api, logger, latestAtStateData.getStateData(), codeBytes);
		state.execute();

		int height = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		long creation = this.atData.getCreation();
		byte[] stateData = state.toBytes();
		byte[] stateHash = Crypto.digest(stateData);
		long atFees = api.calcFinalFees(state);

		this.atStateData = new ATStateData(atAddress, height, creation, stateData, stateHash, atFees);

		return api.getTransactions();
	}

}
