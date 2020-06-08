package org.qortal.controller;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.crosschain.BTC;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.crosschain.CrossChainTradeData.Mode;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

public class TradeBot {

	private static final Logger LOGGER = LogManager.getLogger(TradeBot.class);
	private static final Random RANDOM = new SecureRandom();
	
	private static TradeBot instance;

	private TradeBot() {
		
	}

	public static synchronized TradeBot getInstance() {
		if (instance == null)
			instance = new TradeBot();

		return instance;
	}

	public static String startResponse(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		byte[] tradePrivateKey = generateTradePrivateKey();
		byte[] secret = generateSecret();
		byte[] secretHash = Crypto.digest(secret);

		byte[] tradeNativePublicKey = deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, TradeBotData.State.ALICE_WAITING_FOR_P2SH_A,
				tradeNativePublicKey, tradeNativePublicKeyHash, secret, secretHash, 
				tradeForeignPublicKey, tradeForeignPublicKeyHash, crossChainTradeData.qortalAtAddress, null);
		repository.getCrossChainRepository().save(tradeBotData);

		// P2SH_a to be funded
		byte[] redeemScriptBytes = BTCACCT.buildScript(tradeForeignPublicKeyHash, crossChainTradeData.lockTime, crossChainTradeData.foreignPublicKeyHash, secretHash);
		byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);

		Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
		return p2shAddress.toString();
	}

	private static byte[] generateTradePrivateKey() {
		byte[] seed = new byte[32];
		RANDOM.nextBytes(seed);
		return seed;
	}

	private static byte[] deriveTradeNativePublicKey(byte[] privateKey) {
		return PrivateKeyAccount.toPublicKey(privateKey);
	}

	private static byte[] deriveTradeForeignPublicKey(byte[] privateKey) {
		return ECKey.fromPrivate(privateKey).getPubKey();
	}

	private static byte[] generateSecret() {
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		return secret;
	}

	public void onChainTipChange() {
		// Get repo for trade situations
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			
			for (TradeBotData tradeBotData : allTradeBotData)
				switch (tradeBotData.getState()) {
					case ALICE_START:
						handleAliceStart(repository, tradeBotData);
						break;
				}
		} catch (DataException e) {
			LOGGER.error("Couldn't run trade bot due to repository issue", e);
		}
	}

	private void handleAliceStart(Repository repository, TradeBotData tradeBotData) {
		
	}

}
