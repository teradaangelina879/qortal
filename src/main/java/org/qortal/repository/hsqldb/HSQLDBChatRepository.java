package org.qortal.repository.hsqldb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.qortal.data.chat.ChatMessage;
import org.qortal.repository.ChatRepository;
import org.qortal.repository.DataException;

public class HSQLDBChatRepository implements ChatRepository {

	protected HSQLDBRepository repository;

	public HSQLDBChatRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public List<ChatMessage> getMessagesMatchingCriteria(Long before, Long after, Integer txGroupId,
			List<String> involving, Integer limit, Integer offset, Boolean reverse)
			throws DataException {
		// Check args meet expectations
		if ((txGroupId != null && involving != null && !involving.isEmpty())
				|| (txGroupId == null && (involving == null || involving.size() != 2)))
			throw new DataException("Invalid criteria for fetching chat messages from repository");

		StringBuilder sql = new StringBuilder(1024);

		sql.append("SELECT created_when, tx_group_id, creator, sender, SenderNames.name, "
				+ "recipient, RecipientNames.name, data, is_text, is_encrypted "
				+ "FROM ChatTransactions "
				+ "JOIN Transactions USING (signature) "
				+ "LEFT OUTER JOIN Names AS SenderNames ON SenderNames.owner = sender "
				+ "LEFT OUTER JOIN Names AS RecipientNames ON RecipientNames.owner = recipient ");

		// WHERE clauses

		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();

		// Timestamp range
		if (before != null) {
			whereClauses.add("created_when < ?");
			bindParams.add(before);
		}

		if (after != null) {
			whereClauses.add("created_when > ?");
			bindParams.add(after);
		}

		if (txGroupId != null) {
			whereClauses.add("tx_group_id = " + txGroupId); // int safe to use literally
			whereClauses.add("recipient IS NULL");
		} else {
			whereClauses.add("((sender = ? AND recipient = ?) OR (recipient = ? AND sender = ?))");
			bindParams.addAll(involving);
			bindParams.addAll(involving);
		}

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

		List<ChatMessage> chatMessages = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return chatMessages;

			do {
				long timestamp = resultSet.getLong(1);
				int groupId = resultSet.getInt(2);
				byte[] senderPublicKey = resultSet.getBytes(3);
				String sender = resultSet.getString(4);
				String senderName = resultSet.getString(5);
				String recipient = resultSet.getString(6);
				String recipientName = resultSet.getString(7);
				byte[] data = resultSet.getBytes(8);
				boolean isText = resultSet.getBoolean(9);
				boolean isEncrypted = resultSet.getBoolean(10);

				ChatMessage chatMessage = new ChatMessage(timestamp, groupId, senderPublicKey, sender,
						senderName, recipient, recipientName, data, isText, isEncrypted);

				chatMessages.add(chatMessage);
			} while (resultSet.next());

			return chatMessages;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching chat transactions from repository", e);
		}
	}

}
