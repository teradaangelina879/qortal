package org.qortal.repository.hsqldb;

import static org.qortal.repository.hsqldb.HSQLDBRepository.getZonedTimestampMilli;
import static org.qortal.repository.hsqldb.HSQLDBRepository.toOffsetDateTime;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.repository.ATRepository;
import org.qortal.repository.DataException;

public class HSQLDBATRepository implements ATRepository {

	protected HSQLDBRepository repository;

	public HSQLDBATRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	// ATs

	@Override
	public ATData fromATAddress(String atAddress) throws DataException {
		String sql = "SELECT creator, creation, version, asset_id, code_bytes, code_hash, "
				+ "is_sleeping, sleep_until_height, is_finished, had_fatal_error, "
				+ "is_frozen, frozen_balance "
				+ "FROM ATs "
				+ "WHERE AT_address = ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress)) {
			if (resultSet == null)
				return null;

			byte[] creatorPublicKey = resultSet.getBytes(1);
			long creation = getZonedTimestampMilli(resultSet, 2);
			int version = resultSet.getInt(3);
			long assetId = resultSet.getLong(4);
			byte[] codeBytes = resultSet.getBytes(5); // Actually BLOB
			byte[] codeHash = resultSet.getBytes(6);
			boolean isSleeping = resultSet.getBoolean(7);

			Integer sleepUntilHeight = resultSet.getInt(8);
			if (sleepUntilHeight == 0 && resultSet.wasNull())
				sleepUntilHeight = null;

			boolean isFinished = resultSet.getBoolean(9);
			boolean hadFatalError = resultSet.getBoolean(10);
			boolean isFrozen = resultSet.getBoolean(11);

			BigDecimal frozenBalance = resultSet.getBigDecimal(12);

			return new ATData(atAddress, creatorPublicKey, creation, version, assetId, codeBytes, codeHash,
					isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, frozenBalance);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT from repository", e);
		}
	}

	@Override
	public boolean exists(String atAddress) throws DataException {
		try {
			return this.repository.exists("ATs", "AT_address = ?", atAddress);
		} catch (SQLException e) {
			throw new DataException("Unable to check for AT in repository", e);
		}
	}

	@Override
	public List<ATData> getAllExecutableATs() throws DataException {
		String sql = "SELECT AT_address, creator, creation, version, asset_id, code_bytes, code_hash, "
				+ "is_sleeping, sleep_until_height, had_fatal_error, "
				+ "is_frozen, frozen_balance "
				+ "FROM ATs "
				+ "WHERE is_finished = false "
				+ "ORDER BY creation ASC";

		List<ATData> executableATs = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return executableATs;

			boolean isFinished = false;

			do {
				String atAddress = resultSet.getString(1);
				byte[] creatorPublicKey = resultSet.getBytes(2);
				long creation = getZonedTimestampMilli(resultSet, 3);
				int version = resultSet.getInt(4);
				long assetId = resultSet.getLong(5);
				byte[] codeBytes = resultSet.getBytes(6); // Actually BLOB
				byte[] codeHash = resultSet.getBytes(7);
				boolean isSleeping = resultSet.getBoolean(8);

				Integer sleepUntilHeight = resultSet.getInt(9);
				if (sleepUntilHeight == 0 && resultSet.wasNull())
					sleepUntilHeight = null;

				boolean hadFatalError = resultSet.getBoolean(10);
				boolean isFrozen = resultSet.getBoolean(11);

				BigDecimal frozenBalance = resultSet.getBigDecimal(12);

				ATData atData = new ATData(atAddress, creatorPublicKey, creation, version, assetId, codeBytes, codeHash,
						isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, frozenBalance);

				executableATs.add(atData);
			} while (resultSet.next());

			return executableATs;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch executable ATs from repository", e);
		}
	}

	@Override
	public List<ATData> getATsByFunctionality(byte[] codeHash, Boolean isExecutable, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		sql.append("SELECT AT_address, creator, creation, version, asset_id, code_bytes, ")
				.append("is_sleeping, sleep_until_height, is_finished, had_fatal_error, ")
				.append("is_frozen, frozen_balance ")
				.append("FROM ATs ")
				.append("WHERE code_hash = ? ");

		if (isExecutable != null)
			sql.append("AND is_finished = ").append(isExecutable ? "false" : "true");

		sql.append(" ORDER BY creation ");
		if (reverse != null && reverse)
			sql.append("DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ATData> matchingATs = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), codeHash)) {
			if (resultSet == null)
				return matchingATs;

			do {
				String atAddress = resultSet.getString(1);
				byte[] creatorPublicKey = resultSet.getBytes(2);
				long creation = getZonedTimestampMilli(resultSet, 3);
				int version = resultSet.getInt(4);
				long assetId = resultSet.getLong(5);
				byte[] codeBytes = resultSet.getBytes(6); // Actually BLOB
				boolean isSleeping = resultSet.getBoolean(7);

				Integer sleepUntilHeight = resultSet.getInt(8);
				if (sleepUntilHeight == 0 && resultSet.wasNull())
					sleepUntilHeight = null;

				boolean isFinished = resultSet.getBoolean(9);

				boolean hadFatalError = resultSet.getBoolean(10);
				boolean isFrozen = resultSet.getBoolean(11);

				BigDecimal frozenBalance = resultSet.getBigDecimal(12);

				ATData atData = new ATData(atAddress, creatorPublicKey, creation, version, assetId, codeBytes, codeHash,
						isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, frozenBalance);

				matchingATs.add(atData);
			} while (resultSet.next());

			return matchingATs;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching ATs from repository", e);
		}
	}

	@Override
	public Integer getATCreationBlockHeight(String atAddress) throws DataException {
		String sql = "SELECT height "
				+ "FROM DeployATTransactions "
				+ "JOIN BlockTransactions ON transaction_signature = signature "
				+ "JOIN Blocks ON Blocks.signature = block_signature "
				+ "WHERE AT_address = ? "
				+ "LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress)) {
			if (resultSet == null)
				return null;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT's creation block height from repository", e);
		}
	}

	@Override
	public void save(ATData atData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("ATs");

		saveHelper.bind("AT_address", atData.getATAddress()).bind("creator", atData.getCreatorPublicKey()).bind("creation", toOffsetDateTime(atData.getCreation()))
				.bind("version", atData.getVersion()).bind("asset_id", atData.getAssetId())
				.bind("code_bytes", atData.getCodeBytes()).bind("code_hash", atData.getCodeHash())
				.bind("is_sleeping", atData.getIsSleeping()).bind("sleep_until_height", atData.getSleepUntilHeight())
				.bind("is_finished", atData.getIsFinished()).bind("had_fatal_error", atData.getHadFatalError()).bind("is_frozen", atData.getIsFrozen())
				.bind("frozen_balance", atData.getFrozenBalance());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save AT into repository", e);
		}
	}

	@Override
	public void delete(String atAddress) throws DataException {
		try {
			this.repository.delete("ATs", "AT_address = ?", atAddress);
			// AT States also deleted via ON DELETE CASCADE
		} catch (SQLException e) {
			throw new DataException("Unable to delete AT from repository", e);
		}
	}

	// AT State

	@Override
	public ATStateData getATStateAtHeight(String atAddress, int height) throws DataException {
		String sql = "SELECT creation, state_data, state_hash, fees, is_initial "
				+ "FROM ATStates "
				+ "WHERE AT_address = ? AND height = ? "
				+ "LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress, height)) {
			if (resultSet == null)
				return null;

			long creation = getZonedTimestampMilli(resultSet, 1);
			byte[] stateData = resultSet.getBytes(2); // Actually BLOB
			byte[] stateHash = resultSet.getBytes(3);
			BigDecimal fees = resultSet.getBigDecimal(4);
			boolean isInitial = resultSet.getBoolean(5);

			return new ATStateData(atAddress, height, creation, stateData, stateHash, fees, isInitial);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT state from repository", e);
		}
	}

	@Override
	public ATStateData getLatestATState(String atAddress) throws DataException {
		String sql = "SELECT height, creation, state_data, state_hash, fees, is_initial "
				+ "FROM ATStates "
				+ "WHERE AT_address = ? "
				+ "ORDER BY height DESC "
				+ "LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress)) {
			if (resultSet == null)
				return null;

			int height = resultSet.getInt(1);
			long creation = getZonedTimestampMilli(resultSet, 2);
			byte[] stateData = resultSet.getBytes(3); // Actually BLOB
			byte[] stateHash = resultSet.getBytes(4);
			BigDecimal fees = resultSet.getBigDecimal(5);
			boolean isInitial = resultSet.getBoolean(6);

			return new ATStateData(atAddress, height, creation, stateData, stateHash, fees, isInitial);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch latest AT state from repository", e);
		}
	}

	@Override
	public List<ATStateData> getBlockATStatesAtHeight(int height) throws DataException {
		String sql = "SELECT AT_address, state_hash, fees, is_initial "
				+ "FROM ATStates "
				+ "WHERE height = ? "
				+ "ORDER BY creation ASC";

		List<ATStateData> atStates = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, height)) {
			if (resultSet == null)
				return atStates; // No atStates in this block

			// NB: do-while loop because .checkedExecute() implicitly calls ResultSet.next() for us
			do {
				String atAddress = resultSet.getString(1);
				byte[] stateHash = resultSet.getBytes(2);
				BigDecimal fees = resultSet.getBigDecimal(3);
				boolean isInitial = resultSet.getBoolean(4);

				ATStateData atStateData = new ATStateData(atAddress, height, stateHash, fees, isInitial);
				atStates.add(atStateData);
			} while (resultSet.next());
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT states for this height from repository", e);
		}

		return atStates;
	}

	@Override
	public void save(ATStateData atStateData) throws DataException {
		// We shouldn't ever save partial ATStateData
		if (atStateData.getCreation() == null || atStateData.getStateHash() == null || atStateData.getHeight() == null)
			throw new IllegalArgumentException("Refusing to save partial AT state into repository!");

		HSQLDBSaver saveHelper = new HSQLDBSaver("ATStates");

		saveHelper.bind("AT_address", atStateData.getATAddress()).bind("height", atStateData.getHeight())
				.bind("creation", toOffsetDateTime(atStateData.getCreation())).bind("state_data", atStateData.getStateData())
				.bind("state_hash", atStateData.getStateHash()).bind("fees", atStateData.getFees())
				.bind("is_initial", atStateData.isInitial());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save AT state into repository", e);
		}
	}

	@Override
	public void delete(String atAddress, int height) throws DataException {
		try {
			this.repository.delete("ATStates", "AT_address = ? AND height = ?", atAddress, height);
		} catch (SQLException e) {
			throw new DataException("Unable to delete AT state from repository", e);
		}
	}

	@Override
	public void deleteATStates(int height) throws DataException {
		try {
			this.repository.delete("ATStates", "height = ?", height);
		} catch (SQLException e) {
			throw new DataException("Unable to delete AT states from repository", e);
		}
	}

}
