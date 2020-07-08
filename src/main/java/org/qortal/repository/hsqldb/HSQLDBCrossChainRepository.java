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
	public TradeBotData getTradeBotData(byte[] tradePrivateKey) throws DataException {
		String sql = "SELECT trade_state, at_address, "
				+ "trade_native_public_key, trade_native_public_key_hash, "
				+ "trade_native_address, secret, hash_of_secret, "
				+ "trade_foreign_public_key, trade_foreign_public_key_hash, "
				+ "bitcoin_amount, xprv58, last_transaction_signature, locktime_a "
				+ "FROM TradeBotStates "
				+ "WHERE trade_private_key = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, tradePrivateKey)) {
			if (resultSet == null)
				return null;

			int tradeStateValue = resultSet.getInt(1);
			TradeBotData.State tradeState = TradeBotData.State.valueOf(tradeStateValue);
			if (tradeState == null)
				throw new DataException("Illegal trade-bot trade-state fetched from repository");

			String atAddress = resultSet.getString(2);
			byte[] tradeNativePublicKey = resultSet.getBytes(3);
			byte[] tradeNativePublicKeyHash = resultSet.getBytes(4);
			String tradeNativeAddress = resultSet.getString(5);
			byte[] secret = resultSet.getBytes(6);
			byte[] hashOfSecret = resultSet.getBytes(7);
			byte[] tradeForeignPublicKey = resultSet.getBytes(8);
			byte[] tradeForeignPublicKeyHash = resultSet.getBytes(9);
			long bitcoinAmount = resultSet.getLong(10);
			String xprv58 = resultSet.getString(11);
			byte[] lastTransactionSignature = resultSet.getBytes(12);
			Integer lockTimeA = resultSet.getInt(13);
			if (lockTimeA == 0 && resultSet.wasNull())
				lockTimeA = null;

			return new TradeBotData(tradePrivateKey, tradeState,
					atAddress,
					tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
					secret, hashOfSecret,
					tradeForeignPublicKey, tradeForeignPublicKeyHash,
					bitcoinAmount, xprv58, lastTransactionSignature, lockTimeA);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch trade-bot trading state from repository", e);
		}
	}

	@Override
	public List<TradeBotData> getAllTradeBotData() throws DataException {
		String sql = "SELECT trade_private_key, trade_state, at_address, "
				+ "trade_native_public_key, trade_native_public_key_hash, "
				+ "trade_native_address, secret, hash_of_secret, "
				+ "trade_foreign_public_key, trade_foreign_public_key_hash, "
				+ "bitcoin_amount, xprv58, last_transaction_signature, locktime_a "
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
				byte[] tradeNativePublicKey = resultSet.getBytes(4);
				byte[] tradeNativePublicKeyHash = resultSet.getBytes(5);
				String tradeNativeAddress = resultSet.getString(6);
				byte[] secret = resultSet.getBytes(7);
				byte[] hashOfSecret = resultSet.getBytes(8);
				byte[] tradeForeignPublicKey = resultSet.getBytes(9);
				byte[] tradeForeignPublicKeyHash = resultSet.getBytes(10);
				long bitcoinAmount = resultSet.getLong(11);
				String xprv58 = resultSet.getString(12);
				byte[] lastTransactionSignature = resultSet.getBytes(13);
				Integer lockTimeA = resultSet.getInt(14);
				if (lockTimeA == 0 && resultSet.wasNull())
					lockTimeA = null;

				TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, tradeState,
						atAddress,
						tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
						secret, hashOfSecret,
						tradeForeignPublicKey, tradeForeignPublicKeyHash,
						bitcoinAmount, xprv58, lastTransactionSignature, lockTimeA);
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
				.bind("trade_native_public_key", tradeBotData.getTradeNativePublicKey())
				.bind("trade_native_public_key_hash", tradeBotData.getTradeNativePublicKeyHash())
				.bind("trade_native_address", tradeBotData.getTradeNativeAddress())
				.bind("secret", tradeBotData.getSecret()).bind("hash_of_secret", tradeBotData.getHashOfSecret())
				.bind("trade_foreign_public_key", tradeBotData.getTradeForeignPublicKey())
				.bind("trade_foreign_public_key_hash", tradeBotData.getTradeForeignPublicKeyHash())
				.bind("bitcoin_amount", tradeBotData.getBitcoinAmount())
				.bind("xprv58", tradeBotData.getXprv58())
				.bind("last_transaction_signature", tradeBotData.getLastTransactionSignature())
				.bind("locktime_a", tradeBotData.getLockTimeA());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save trade bot data into repository", e);
		}
	}

	@Override
	public int delete(byte[] tradePrivateKey) throws DataException {
		try {
			return this.repository.delete("TradeBotStates", "trade_private_key = ?", tradePrivateKey);
		} catch (SQLException e) {
			throw new DataException("Unable to delete trade-bot states from repository", e);
		}
	}

}