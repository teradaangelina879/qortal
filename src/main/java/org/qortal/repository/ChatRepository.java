package org.qortal.repository;

import java.util.List;

import org.qortal.data.transaction.ChatTransactionData;

public interface ChatRepository {

	public List<ChatTransactionData> getTransactionsMatchingCriteria(
			Long before,
			Long after,
			Integer txGroupId,
			String senderAddress,
			String recipientAddress,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

}
