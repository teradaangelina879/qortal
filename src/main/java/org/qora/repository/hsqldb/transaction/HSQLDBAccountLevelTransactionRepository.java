package org.qora.repository.hsqldb.transaction;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.qora.data.transaction.AccountLevelTransactionData;
import org.qora.data.transaction.BaseTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.hsqldb.HSQLDBRepository;
import org.qora.repository.hsqldb.HSQLDBSaver;

public class HSQLDBAccountLevelTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBAccountLevelTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT target, level, previous_level FROM AccountLevelTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String target = resultSet.getString(1);
			int level = resultSet.getInt(2);

			Integer previousLevel = resultSet.getInt(3);
			if (previousLevel == 0 && resultSet.wasNull())
				previousLevel = null;

			return new AccountLevelTransactionData(baseTransactionData, target, level, previousLevel);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account level transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		AccountLevelTransactionData accountLevelTransactionData = (AccountLevelTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("AccountLevelTransactions");

		saveHelper.bind("signature", accountLevelTransactionData.getSignature()).bind("creator", accountLevelTransactionData.getCreatorPublicKey())
				.bind("target", accountLevelTransactionData.getTarget()).bind("level", accountLevelTransactionData.getLevel());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account level transaction into repository", e);
		}
	}

}
