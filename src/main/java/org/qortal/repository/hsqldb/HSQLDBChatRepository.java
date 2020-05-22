package org.qortal.repository.hsqldb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.qortal.data.chat.ActiveChats;
import org.qortal.data.chat.ActiveChats.DirectChat;
import org.qortal.data.chat.ActiveChats.GroupChat;
import org.qortal.data.chat.ChatMessage;
import org.qortal.repository.ChatRepository;
import org.qortal.repository.DataException;
import org.qortal.transaction.Transaction.TransactionType;

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

		sql.append("SELECT created_when, tx_group_id, Transactions.reference, creator, "
				+ "sender, SenderNames.name, recipient, RecipientNames.name, "
				+ "data, is_text, is_encrypted "
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
				byte[] reference = resultSet.getBytes(3);
				byte[] senderPublicKey = resultSet.getBytes(4);
				String sender = resultSet.getString(5);
				String senderName = resultSet.getString(6);
				String recipient = resultSet.getString(7);
				String recipientName = resultSet.getString(8);
				byte[] data = resultSet.getBytes(9);
				boolean isText = resultSet.getBoolean(10);
				boolean isEncrypted = resultSet.getBoolean(11);

				ChatMessage chatMessage = new ChatMessage(timestamp, groupId, reference, senderPublicKey, sender,
						senderName, recipient, recipientName, data, isText, isEncrypted);

				chatMessages.add(chatMessage);
			} while (resultSet.next());

			return chatMessages;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching chat transactions from repository", e);
		}
	}

	@Override
	public ActiveChats getActiveChats(String address) throws DataException {
		List<GroupChat> groupChats = getActiveGroupChats(address);
		List<DirectChat> directChats = getActiveDirectChats(address);

		return new ActiveChats(groupChats, directChats);
	}

	private List<GroupChat> getActiveGroupChats(String address) throws DataException {
		// Find groups where address is a member and potential latest timestamp
		String groupsSql = "SELECT group_id, group_name, ("
					+ "SELECT created_when "
					+ "FROM Transactions "
					// NOTE: We need to qualify "Groups.group_id" here to avoid "General error" bug in HSQLDB v2.5.0
					+ "WHERE tx_group_id = Groups.group_id AND type = " + TransactionType.CHAT.value + " "
					+ "ORDER BY created_when DESC "
					+ "LIMIT 1"
				+ ") AS latest_timestamp "
				+ "FROM GroupMembers "
				+ "JOIN Groups USING (group_id) "
				+ "WHERE address = ?";

		List<GroupChat> groupChats = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(groupsSql, address)) {
			if (resultSet != null) {
				do {
					int groupId = resultSet.getInt(1);
					String groupName = resultSet.getString(2);

					Long timestamp = resultSet.getLong(3);
					if (timestamp == 0 && resultSet.wasNull())
						timestamp = null;

					GroupChat groupChat = new GroupChat(groupId, groupName, timestamp);
					groupChats.add(groupChat);
				} while (resultSet.next());
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch active group chats from repository", e);
		}

		// We need different SQL to handle group-less chat
		String grouplessSql = "SELECT created_when "
				+ "FROM ChatTransactions "
				+ "JOIN Transactions USING (signature) "
				+ "WHERE tx_group_id = 0 "
				+ "AND recipient IS NULL "
				+ "ORDER BY created_when DESC "
				+ "LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(grouplessSql)) {
			Long timestamp = null;

			if (resultSet != null)
				// We found a recipient-less, group-less CHAT message, so report its timestamp
				timestamp = resultSet.getLong(1);

			GroupChat groupChat = new GroupChat(0, null, timestamp);
			groupChats.add(groupChat);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch active group chats from repository", e);
		}

		return groupChats;
	}

	private List<DirectChat> getActiveDirectChats(String address) throws DataException {
		// Find chat messages involving address
		String directSql = "SELECT other_address, name, latest_timestamp "
				+ "FROM ("
					+ "SELECT recipient FROM ChatTransactions "
					+ "WHERE sender = ? AND recipient IS NOT NULL "
					+ "UNION "
					+ "SELECT sender FROM ChatTransactions "
					+ "WHERE recipient = ?"
				+ ") AS OtherParties (other_address) "
				+ "CROSS JOIN LATERAL("
					+ "SELECT created_when FROM ChatTransactions "
					+ "NATURAL JOIN Transactions "
					+ "WHERE (sender = other_address AND recipient = ?) "
					+ "OR (sender = ? AND recipient = other_address) "
					+ "ORDER BY created_when DESC "
					+ "LIMIT 1"
				+ ") AS LatestMessages (latest_timestamp) "
				+ "LEFT OUTER JOIN Names ON owner = other_address";

		Object[] bindParams = new Object[] { address, address, address, address };

		List<DirectChat> directChats = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(directSql, bindParams)) {
			if (resultSet == null)
				return directChats;

			do {
				String otherAddress = resultSet.getString(1);
				String name = resultSet.getString(2);
				long timestamp = resultSet.getLong(3);

				DirectChat directChat = new DirectChat(otherAddress, name, timestamp);
				directChats.add(directChat);
			} while (resultSet.next());
		} catch (SQLException e) {
			throw new DataException("Unable to fetch active direct chats from repository", e);
		}

		return directChats;
	}

}
