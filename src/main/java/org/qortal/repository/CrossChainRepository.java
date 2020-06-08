package org.qortal.repository;

import java.util.List;

import org.qortal.data.crosschain.TradeBotData;

public interface CrossChainRepository {

	public List<TradeBotData> getAllTradeBotData() throws DataException;

}
