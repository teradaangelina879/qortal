package org.qortal.repository.hsqldb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.CrossChainRepository;
import org.qortal.repository.DataException;

public class HSQLDBCrossChainRepository implements CrossChainRepository {

	protected HSQLDBRepository repository;

	public HSQLDBCrossChainRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public List<TradeBotData> getAllTradeBotData() throws DataException {
		String sql = "SELECT trade_private_key, trade_state, secret, at_address, last_transaction_signature "
				+ "FROM TradeBotStates";

		List<TradeBotData> allTradeBotData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return allTradeBotData;

			do {
				byte[] tradePrivateKey = resultSet.getBytes(1);
				int tradeStateValue = resultSet.getInt(2);
				TradeBotData.State tradeState = TradeBotData.State.valueOf(tradeStateValue);
				if (tradeState == null)
					throw new DataException("Illegal trade-bot trade-state fetched from repository");

				byte[] secret = resultSet.getBytes(3);
				String atAddress = resultSet.getString(4);
				byte[] lastTransactionSignature = resultSet.getBytes(5);

				TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, tradeState, secret, atAddress, lastTransactionSignature);
				allTradeBotData.add(tradeBotData);
			} while (resultSet.next());

			return allTradeBotData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch trade-bot trading states from repository", e);
		}
	}

}
