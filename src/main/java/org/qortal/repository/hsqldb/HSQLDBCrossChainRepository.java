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
		String sql = "SELECT trade_private_key, trade_state, at_address, trade_timeout, "
				+ "trade_native_public_key, trade_native_public_key_hash, "
				+ "secret, secret_hash, "
				+ "trade_foreign_public_key, trade_foreign_public_key_hash, "
				+ "bitcoin_amount, last_transaction_signature "
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

				String atAddress = resultSet.getString(3);
				int tradeTimeout = resultSet.getInt(4);
				byte[] tradeNativePublicKey = resultSet.getBytes(5);
				byte[] tradeNativePublicKeyHash = resultSet.getBytes(6);
				byte[] secret = resultSet.getBytes(7);
				byte[] secretHash = resultSet.getBytes(8);
				byte[] tradeForeignPublicKey = resultSet.getBytes(9);
				byte[] tradeForeignPublicKeyHash = resultSet.getBytes(10);
				long bitcoinAmount = resultSet.getLong(11);
				byte[] lastTransactionSignature = resultSet.getBytes(12);

				TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, tradeState,
						atAddress, tradeTimeout,
						tradeNativePublicKey, tradeNativePublicKeyHash, secret, secretHash, 
						tradeForeignPublicKey, tradeForeignPublicKeyHash,
						bitcoinAmount, lastTransactionSignature);
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
				.bind("at_address", tradeBotData.getAtAddress())
				.bind("trade_timeout", tradeBotData.getTradeTimeout())
				.bind("trade_native_public_key", tradeBotData.getTradeNativePublicKey())
				.bind("trade_native_public_key_hash", tradeBotData.getTradeNativePublicKeyHash())
				.bind("secret", tradeBotData.getSecret()).bind("secret_hash", tradeBotData.getSecretHash())
				.bind("trade_foreign_public_key", tradeBotData.getTradeForeignPublicKey())
				.bind("trade_foreign_public_key_hash", tradeBotData.getTradeForeignPublicKeyHash())
				.bind("bitcoin_amount", tradeBotData.getBitcoinAmount())
				.bind("last_transaction_signature", tradeBotData.getLastTransactionSignature());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save trade bot data into repository", e);
		}
	}

}
