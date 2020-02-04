package org.qortal.repository.hsqldb.transaction;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.IssueAssetTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.repository.hsqldb.HSQLDBSaver;

public class HSQLDBIssueAssetTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBIssueAssetTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT owner, asset_name, description, quantity, is_divisible, data, is_unspendable, asset_id FROM IssueAssetTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String owner = resultSet.getString(1);
			String assetName = resultSet.getString(2);
			String description = resultSet.getString(3);
			long quantity = resultSet.getLong(4);
			boolean isDivisible = resultSet.getBoolean(5);
			String data = resultSet.getString(6);
			boolean isUnspendable = resultSet.getBoolean(7);

			// Special null-checking for asset ID
			Long assetId = resultSet.getLong(8);
			if (assetId == 0 && resultSet.wasNull())
				assetId = null;

			return new IssueAssetTransactionData(baseTransactionData, assetId, owner, assetName, description, quantity, isDivisible,
					data, isUnspendable);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch issue asset transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		IssueAssetTransactionData issueAssetTransactionData = (IssueAssetTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("IssueAssetTransactions");

		saveHelper.bind("signature", issueAssetTransactionData.getSignature()).bind("issuer", issueAssetTransactionData.getIssuerPublicKey())
				.bind("owner", issueAssetTransactionData.getOwner()).bind("asset_name", issueAssetTransactionData.getAssetName())
				.bind("description", issueAssetTransactionData.getDescription()).bind("quantity", issueAssetTransactionData.getQuantity())
				.bind("is_divisible", issueAssetTransactionData.getIsDivisible()).bind("data", issueAssetTransactionData.getData())
				.bind("is_unspendable", issueAssetTransactionData.getIsUnspendable()).bind("asset_id", issueAssetTransactionData.getAssetId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save issue asset transaction into repository", e);
		}
	}

}
