package org.qortal.repository;

import java.util.List;

import org.qortal.data.crosschain.TradeBotData;

public interface CrossChainRepository {

	public TradeBotData getTradeBotData(byte[] tradePrivateKey) throws DataException;

	public List<TradeBotData> getAllTradeBotData() throws DataException;

	public void save(TradeBotData tradeBotData) throws DataException;

	/** Delete trade-bot states using passed private key. */
	public int delete(byte[] tradePrivateKey) throws DataException;

}
