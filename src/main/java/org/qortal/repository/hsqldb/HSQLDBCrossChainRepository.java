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
		String sql = "SELECT trade_private_key, trade_state, trade_native_public_key, trade_native_public_key_hash, "
				+ "secret, secret_hash, trade_foreign_public_key, trade_foreign_public_key_hash, at_address, last_transaction_signature "
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

				byte[] tradeNativePublicKey = resultSet.getBytes(3);
				byte[] tradeNativePublicKeyHash = resultSet.getBytes(4);
				byte[] secret = resultSet.getBytes(5);
				byte[] secretHash = resultSet.getBytes(6);
				byte[] tradeForeignPublicKey = resultSet.getBytes(7);
				byte[] tradeForeignPublicKeyHash = resultSet.getBytes(8);
				String atAddress = resultSet.getString(9);
				byte[] lastTransactionSignature = resultSet.getBytes(10);

				TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, tradeState,
						tradeNativePublicKey, tradeNativePublicKeyHash, secret, secretHash, 
						tradeForeignPublicKey, tradeForeignPublicKeyHash, atAddress, lastTransactionSignature);
				allTradeBotData.add(tradeBotData);
			} while (resultSet.next());

			return allTradeBotData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch trade-bot trading states from repository", e);
		}
	}

	@Override
	public void save(TradeBotData tradeBotData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("TradeBotStates");

		saveHelper.bind("trade_private_key", tradeBotData.getTradePrivateKey())
				.bind("trade_state", tradeBotData.getState().value)
				.bind("trade_native_public_key", tradeBotData.getTradeNativePublicKey())
				.bind("trade_native_public_key_hash", tradeBotData.getTradeNativePublicKeyHash())
				.bind("secret", tradeBotData.getSecret()).bind("secret_hash", tradeBotData.getSecretHash())
				.bind("trade_foreign_public_key", tradeBotData.getTradeForeignPublicKey())
				.bind("trade_foreign_public_key_hash", tradeBotData.getTradeForeignPublicKeyHash())
				.bind("at_address", tradeBotData.getAtAddress())
				.bind("last_transaction_signature", tradeBotData.getLastTransactionSignature());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save trade bot data into repository", e);
		}
	}

}
