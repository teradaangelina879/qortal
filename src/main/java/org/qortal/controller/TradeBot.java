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
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.model.TradeBotCreateRequest;
import org.qortal.asset.Asset;
import org.qortal.crosschain.BTC;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crypto.Crypto;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transform.transaction.DeployAtTransactionTransformer;
import org.qortal.utils.NTP;

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

	public static byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		byte[] tradePrivateKey = generateTradePrivateKey();
		byte[] secret = generateSecret();
		byte[] secretHash = Crypto.digest(secret);

		byte[] tradeNativePublicKey = deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		PublicKeyAccount creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);

		// Deploy AT
		long timestamp = NTP.getTime();
		byte[] reference = creator.getLastReference();
		long fee = 0L;
		byte[] signature = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, creator.getPublicKey(), fee, signature);

		String name = "QORT/BTC ACCT";
		String description = "QORT/BTC cross-chain trade";
		String aTType = "ACCT";
		String tags = "ACCT QORT BTC";
		byte[] creationBytes = BTCACCT.buildQortalAT(creator.getAddress(), tradeNativePublicKeyHash, secretHash, tradeBotCreateRequest.tradeTimeout, tradeBotCreateRequest.qortAmount, tradeBotCreateRequest.bitcoinAmount);
		long amount = tradeBotCreateRequest.fundingQortAmount;

		DeployAtTransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, aTType, tags, creationBytes, amount, Asset.QORT);
		DeployAtTransaction.ensureATAddress(deployAtTransactionData);
		String atAddress = deployAtTransactionData.getAtAddress();

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, TradeBotData.State.BOB_WAITING_FOR_MESSAGE,
				tradeNativePublicKey, tradeNativePublicKeyHash, secret, secretHash,
				tradeForeignPublicKey, tradeForeignPublicKeyHash, atAddress, null);
		repository.getCrossChainRepository().save(tradeBotData);

		// Return to user for signing and broadcast as we don't have their Qortal private key
		return DeployAtTransactionTransformer.toBytes(deployAtTransactionData);
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
					case BOB_WAITING_FOR_MESSAGE:
						handleBobWaitingForMessage(repository, tradeBotData);
						break;
				}
		} catch (DataException e) {
			LOGGER.error("Couldn't run trade bot due to repository issue", e);
		}
	}

	private void handleBobWaitingForMessage(Repository repository, TradeBotData tradeBotData) {
		
	}

}
