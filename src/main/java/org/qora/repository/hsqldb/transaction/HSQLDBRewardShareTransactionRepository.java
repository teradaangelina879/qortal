package org.qora.repository.hsqldb.transaction;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.qora.data.transaction.BaseTransactionData;
import org.qora.data.transaction.RewardShareTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.hsqldb.HSQLDBRepository;
import org.qora.repository.hsqldb.HSQLDBSaver;

public class HSQLDBRewardShareTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBRewardShareTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT recipient, reward_share_public_key, share_percent, previous_share_percent FROM RewardShareTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String recipient = resultSet.getString(1);
			byte[] rewardSharePublicKey = resultSet.getBytes(2);
			BigDecimal sharePercent = resultSet.getBigDecimal(3);
			BigDecimal previousSharePercent = resultSet.getBigDecimal(4);

			return new RewardShareTransactionData(baseTransactionData, recipient, rewardSharePublicKey, sharePercent, previousSharePercent);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward-share transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		RewardShareTransactionData rewardShareTransactionData = (RewardShareTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("RewardShareTransactions");

		saveHelper.bind("signature", rewardShareTransactionData.getSignature()).bind("minter_public_key", rewardShareTransactionData.getMinterPublicKey())
				.bind("recipient", rewardShareTransactionData.getRecipient()).bind("reward_share_public_key", rewardShareTransactionData.getRewardSharePublicKey())
				.bind("share_percent", rewardShareTransactionData.getSharePercent()).bind("previous_share_percent", rewardShareTransactionData.getPreviousSharePercent());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save reward-share transaction into repository", e);
		}
	}

}
