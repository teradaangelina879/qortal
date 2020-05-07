package org.qortal.repository.hsqldb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.repository.ChatRepository;
import org.qortal.repository.DataException;
import org.qortal.transaction.Transaction.TransactionType;

public class HSQLDBChatRepository implements ChatRepository {

	protected HSQLDBRepository repository;

	public HSQLDBChatRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public List<ChatTransactionData> getTransactionsMatchingCriteria(Long before, Long after, Integer txGroupId,
			String senderAddress, String recipientAddress, Integer limit, Integer offset, Boolean reverse)
			throws DataException {
		boolean hasSenderAddress = senderAddress != null && !senderAddress.isEmpty();
		boolean hasRecipientAddress = recipientAddress != null && !recipientAddress.isEmpty();

		String signatureColumn = "Transactions.signature";
		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();

		// Tables, starting with Transactions
		StringBuilder tables = new StringBuilder(256);
		tables.append("Transactions");

		if (hasSenderAddress || hasRecipientAddress)
			tables.append(" JOIN ChatTransactions USING (signature)");

		// WHERE clauses next

		// CHAT transaction type
		whereClauses.add("Transactions.type = " + TransactionType.CHAT.value);

		// Timestamp range
		if (before != null) {
			whereClauses.add("Transactions.created_when < ?");
			bindParams.add(before);
		}

		if (after != null) {
			whereClauses.add("Transactions.created_when > ?");
			bindParams.add(after);
		}

		if (txGroupId != null)
			whereClauses.add("Transactions.tx_group_id = " + txGroupId);

		if (hasSenderAddress) {
			whereClauses.add("ChatTransactions.sender = ?");
			bindParams.add(senderAddress);
		}

		if (hasRecipientAddress) {
			whereClauses.add("ChatTransactions.recipient = ?");
			bindParams.add(recipientAddress);
		}

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT ");
		sql.append(signatureColumn);
		sql.append(" FROM ");
		sql.append(tables);

		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			final int whereClausesSize = whereClauses.size();
			for (int wci = 0; wci < whereClausesSize; ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		sql.append(" ORDER BY Transactions.created_when");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ChatTransactionData> chatTransactionsData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return chatTransactionsData;

			do {
				byte[] signature = resultSet.getBytes(1);

				chatTransactionsData.add((ChatTransactionData) this.repository.getTransactionRepository().fromSignature(signature));
			} while (resultSet.next());

			return chatTransactionsData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching chat transactions from repository", e);
		}
	}

}
