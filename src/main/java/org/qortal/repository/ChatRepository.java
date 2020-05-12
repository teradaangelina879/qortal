package org.qortal.repository;

import java.util.List;

import org.qortal.data.chat.ChatMessage;

public interface ChatRepository {

	/**
	 * Returns CHAT messages matching criteria.
	 * <p>
	 * Expects EITHER non-null txGroupID OR non-null sender and recipient addresses.
	 */
	public List<ChatMessage> getMessagesMatchingCriteria(Long before, Long after,
			Integer txGroupId, List<String> involving,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

}
