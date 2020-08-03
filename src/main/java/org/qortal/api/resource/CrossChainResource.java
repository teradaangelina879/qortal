package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script.ScriptType;
import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.api.model.CrossChainCancelRequest;
import org.qortal.api.model.CrossChainSecretRequest;
import org.qortal.api.model.CrossChainTradeRequest;
import org.qortal.api.model.TradeBotCreateRequest;
import org.qortal.api.model.TradeBotRespondRequest;
import org.qortal.api.model.CrossChainBitcoinP2SHStatus;
import org.qortal.api.model.CrossChainBitcoinRedeemRequest;
import org.qortal.api.model.CrossChainBitcoinRefundRequest;
import org.qortal.api.model.CrossChainBitcoinTemplateRequest;
import org.qortal.api.model.CrossChainBuildRequest;
import org.qortal.asset.Asset;
import org.qortal.controller.TradeBot;
import org.qortal.crosschain.BTC;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crosschain.BTCP2SH;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.DeployAtTransactionTransformer;
import org.qortal.transform.transaction.MessageTransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

@Path("/crosschain")
@Tag(name = "Cross-Chain")
public class CrossChainResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/tradeoffers")
	@Operation(
		summary = "Find cross-chain trade offers",
		responses = {
			@ApiResponse(
				description = "automated transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = CrossChainTradeData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<CrossChainTradeData> getTradeOffers(
			@Parameter( ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter( ref = "offset" ) @QueryParam("offset") Integer offset,
			@Parameter( ref = "reverse" ) @QueryParam("reverse") Boolean reverse) {
		// Impose a limit on 'limit'
		if (limit != null && limit > 100)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		byte[] codeHash = BTCACCT.CODE_BYTES_HASH;
		boolean isExecutable = true;

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<ATData> atsData = repository.getATRepository().getATsByFunctionality(codeHash, isExecutable, limit, offset, reverse);

			List<CrossChainTradeData> crossChainTradesData = new ArrayList<>();
			for (ATData atData : atsData) {
				CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);
				crossChainTradesData.add(crossChainTradeData);
			}

			return crossChainTradesData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/build")
	@Operation(
		summary = "Build cross-chain trading AT",
		description = "Returns raw, unsigned DEPLOY_AT transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainBuildRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_DATA, ApiError.INVALID_REFERENCE, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String buildTrade(CrossChainBuildRequest tradeRequest) {
		Security.checkApiCallAllowed(request);

		byte[] creatorPublicKey = tradeRequest.creatorPublicKey;

		if (creatorPublicKey == null || creatorPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (tradeRequest.hashOfSecretB == null || tradeRequest.hashOfSecretB.length != BTC.HASH160_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.tradeTimeout == null)
			tradeRequest.tradeTimeout = 7 * 24 * 60; // 7 days
		else
			if (tradeRequest.tradeTimeout < 10 || tradeRequest.tradeTimeout > 50000)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.qortAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.fundingQortAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		// funding amount must exceed initial + final
		if (tradeRequest.fundingQortAmount <= tradeRequest.qortAmount)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.bitcoinAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PublicKeyAccount creatorAccount = new PublicKeyAccount(repository, creatorPublicKey);

			byte[] creationBytes = BTCACCT.buildQortalAT(creatorAccount.getAddress(), tradeRequest.bitcoinPublicKeyHash, tradeRequest.hashOfSecretB,
					tradeRequest.qortAmount, tradeRequest.bitcoinAmount, tradeRequest.tradeTimeout);

			long txTimestamp = NTP.getTime();
			byte[] lastReference = creatorAccount.getLastReference();
			if (lastReference == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_REFERENCE);

			long fee = 0;
			String name = "QORT-BTC cross-chain trade";
			String description = "Qortal-Bitcoin cross-chain trade";
			String atType = "ACCT";
			String tags = "QORT-BTC ACCT";

			BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, creatorAccount.getPublicKey(), fee, null);
			TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, atType, tags, creationBytes, tradeRequest.fundingQortAmount, Asset.QORT);

			Transaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);

			fee = deployAtTransaction.calcRecommendedFee();
			deployAtTransactionData.setFee(fee);

			ValidationResult result = deployAtTransaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = DeployAtTransactionTransformer.toBytes(deployAtTransactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/tradeoffer/trademessage")
	@Operation(
		summary = "Builds raw, unsigned 'trade' MESSAGE transaction that sends cross-chain trade recipient address, triggering 'trade' mode",
		description = "Specify address of cross-chain AT that needs to be messaged, and signature of 'offer' MESSAGE from trade partner.<br>"
			+ "AT needs to be in 'offer' mode. Messages sent to an AT in any other mode will be ignored, but still cost fees to send!<br>"
			+ "You need to sign output with trade private key otherwise the MESSAGE transaction will be invalid.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainTradeRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public String buildTradeMessage(CrossChainTradeRequest tradeRequest) {
		Security.checkApiCallAllowed(request);

		byte[] tradePublicKey = tradeRequest.tradePublicKey;

		if (tradePublicKey == null || tradePublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (tradeRequest.atAddress == null || !Crypto.isValidAtAddress(tradeRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (tradeRequest.messageTransactionSignature == null || !Crypto.isValidAddress(tradeRequest.messageTransactionSignature))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, tradeRequest.atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode != BTCACCT.Mode.OFFERING)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Does supplied public key match trade public key?
			if (!Crypto.toAddress(tradePublicKey).equals(crossChainTradeData.qortalCreatorTradeAddress))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

			TransactionData transactionData = repository.getTransactionRepository().fromSignature(tradeRequest.messageTransactionSignature);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSACTION_UNKNOWN);

			if (transactionData.getType() != TransactionType.MESSAGE)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSACTION_INVALID);

			MessageTransactionData messageTransactionData = (MessageTransactionData) transactionData;
			byte[] messageData = messageTransactionData.getData();
			BTCACCT.OfferMessageData offerMessageData = BTCACCT.extractOfferMessageData(messageData);
			if (offerMessageData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSACTION_INVALID);

			// Good to make MESSAGE

			byte[] aliceForeignPublicKeyHash = offerMessageData.partnerBitcoinPKH;
			byte[] hashOfSecretA = offerMessageData.hashOfSecretA;
			int lockTimeA = (int) offerMessageData.lockTimeA;

			String aliceNativeAddress = Crypto.toAddress(messageTransactionData.getCreatorPublicKey());
			int lockTimeB = BTCACCT.calcLockTimeB(messageTransactionData.getTimestamp(), lockTimeA);

			byte[] outgoingMessageData = BTCACCT.buildTradeMessage(aliceNativeAddress, aliceForeignPublicKeyHash, hashOfSecretA, lockTimeA, lockTimeB);
			byte[] messageTransactionBytes = buildAtMessage(repository, tradePublicKey, tradeRequest.atAddress, outgoingMessageData);

			return Base58.encode(messageTransactionBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/tradeoffer/redeemmessage")
	@Operation(
		summary = "Builds raw, unsigned 'redeem' MESSAGE transaction that sends secrets to AT, releasing funds to partner",
		description = "Specify address of cross-chain AT that needs to be messaged, both 32-byte secrets and an address for receiving QORT from AT.<br>"
			+ "AT needs to be in 'trade' mode. Messages sent to an AT in any other mode will be ignored, but still cost fees to send!<br>"
			+ "You need to sign output with account the AT considers the trade 'partner' otherwise the MESSAGE transaction will be invalid.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainSecretRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public String buildRedeemMessage(CrossChainSecretRequest secretRequest) {
		Security.checkApiCallAllowed(request);

		byte[] partnerPublicKey = secretRequest.partnerPublicKey;

		if (partnerPublicKey == null || partnerPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (secretRequest.atAddress == null || !Crypto.isValidAtAddress(secretRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (secretRequest.secretA == null || secretRequest.secretA.length != BTCACCT.SECRET_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (secretRequest.secretB == null || secretRequest.secretB.length != BTCACCT.SECRET_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (secretRequest.receivingAddress == null || !Crypto.isValidAddress(secretRequest.receivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, secretRequest.atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode != BTCACCT.Mode.TRADING)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			String partnerAddress = Crypto.toAddress(partnerPublicKey);

			// MESSAGE must come from address that AT considers trade partner
			if (!crossChainTradeData.qortalPartnerAddress.equals(partnerAddress))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			// Good to make MESSAGE

			byte[] messageData = BTCACCT.buildRedeemMessage(secretRequest.secretA, secretRequest.secretB, secretRequest.receivingAddress);
			byte[] messageTransactionBytes = buildAtMessage(repository, partnerPublicKey, secretRequest.atAddress, messageData);

			return Base58.encode(messageTransactionBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@DELETE
	@Path("/tradeoffer")
	@Operation(
		summary = "Builds raw, unsigned 'cancel' MESSAGE transaction that cancels cross-chain trade offer",
		description = "Specify address of cross-chain AT that needs to be cancelled.<br>"
			+ "AT needs to be in 'offer' mode. Messages sent to an AT in 'trade' mode will be ignored, but still cost fees to send!<br>"
			+ "You need to sign output with trade's private key otherwise the MESSAGE transaction will be invalid.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainCancelRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public String buildCancelMessage(CrossChainCancelRequest cancelRequest) {
		Security.checkApiCallAllowed(request);

		byte[] tradePublicKey = cancelRequest.tradePublicKey;

		if (tradePublicKey == null || tradePublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (cancelRequest.atAddress == null || !Crypto.isValidAtAddress(cancelRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, cancelRequest.atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode != BTCACCT.Mode.OFFERING)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Does supplied public key match trade public key?
			if (!Crypto.toAddress(tradePublicKey).equals(crossChainTradeData.qortalCreatorTradeAddress))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

			// Good to make MESSAGE

			String atCreatorAddress = crossChainTradeData.qortalCreator;
			byte[] messageData = BTCACCT.buildCancelMessage(atCreatorAddress);

			byte[] messageTransactionBytes = buildAtMessage(repository, tradePublicKey, cancelRequest.atAddress, messageData);

			return Base58.encode(messageTransactionBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/p2sh/a")
	@Operation(
		summary = "Returns Bitcoin P2SH-A address based on trade info",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainBitcoinTemplateRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public String deriveP2shA(CrossChainBitcoinTemplateRequest templateRequest) {
		Security.checkApiCallAllowed(request);

		return deriveP2sh(templateRequest, (crossChainTradeData) -> crossChainTradeData.lockTimeA, (crossChainTradeData) -> crossChainTradeData.hashOfSecretA);
	}

	@POST
	@Path("/p2sh/b")
	@Operation(
		summary = "Returns Bitcoin P2SH-B address based on trade info",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainBitcoinTemplateRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public String deriveP2shB(CrossChainBitcoinTemplateRequest templateRequest) {
		Security.checkApiCallAllowed(request);

		return deriveP2sh(templateRequest, (crossChainTradeData) -> crossChainTradeData.lockTimeB, (crossChainTradeData) -> crossChainTradeData.hashOfSecretB);
	}

	private String deriveP2sh(CrossChainBitcoinTemplateRequest templateRequest, ToIntFunction<CrossChainTradeData> lockTimeFn, Function<CrossChainTradeData, byte[]> hashOfSecretFn) {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		if (templateRequest.refundPublicKeyHash == null || templateRequest.refundPublicKeyHash.length != 20)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (templateRequest.redeemPublicKeyHash == null || templateRequest.redeemPublicKeyHash.length != 20)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (templateRequest.atAddress == null || !Crypto.isValidAtAddress(templateRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, templateRequest.atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == BTCACCT.Mode.OFFERING || crossChainTradeData.mode == BTCACCT.Mode.CANCELLED)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			byte[] redeemScriptBytes = BTCP2SH.buildScript(templateRequest.refundPublicKeyHash, lockTimeFn.applyAsInt(crossChainTradeData), templateRequest.redeemPublicKeyHash, hashOfSecretFn.apply(crossChainTradeData));
			byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
			return p2shAddress.toString();
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/p2sh/a/check")
	@Operation(
		summary = "Checks Bitcoin P2SH-A address based on trade info",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainBitcoinTemplateRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = CrossChainBitcoinP2SHStatus.class))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN, ApiError.REPOSITORY_ISSUE})
	public CrossChainBitcoinP2SHStatus checkP2shA(CrossChainBitcoinTemplateRequest templateRequest) {
		Security.checkApiCallAllowed(request);

		return checkP2sh(templateRequest, (crossChainTradeData) -> crossChainTradeData.lockTimeA, (crossChainTradeData) -> crossChainTradeData.hashOfSecretA);
	}

	@POST
	@Path("/p2sh/b/check")
	@Operation(
		summary = "Checks Bitcoin P2SH-B address based on trade info",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainBitcoinTemplateRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = CrossChainBitcoinP2SHStatus.class))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN, ApiError.REPOSITORY_ISSUE})
	public CrossChainBitcoinP2SHStatus checkP2shB(CrossChainBitcoinTemplateRequest templateRequest) {
		Security.checkApiCallAllowed(request);

		return checkP2sh(templateRequest, (crossChainTradeData) -> crossChainTradeData.lockTimeB, (crossChainTradeData) -> crossChainTradeData.hashOfSecretB);
	}

	private CrossChainBitcoinP2SHStatus checkP2sh(CrossChainBitcoinTemplateRequest templateRequest, ToIntFunction<CrossChainTradeData> lockTimeFn, Function<CrossChainTradeData, byte[]> hashOfSecretFn) {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		if (templateRequest.refundPublicKeyHash == null || templateRequest.refundPublicKeyHash.length != 20)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (templateRequest.redeemPublicKeyHash == null || templateRequest.redeemPublicKeyHash.length != 20)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (templateRequest.atAddress == null || !Crypto.isValidAtAddress(templateRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, templateRequest.atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == BTCACCT.Mode.OFFERING || crossChainTradeData.mode == BTCACCT.Mode.CANCELLED)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			int lockTime = lockTimeFn.applyAsInt(crossChainTradeData);
			byte[] hashOfSecret = hashOfSecretFn.apply(crossChainTradeData);

			byte[] redeemScriptBytes = BTCP2SH.buildScript(templateRequest.refundPublicKeyHash, lockTime, templateRequest.redeemPublicKeyHash, hashOfSecret);
			byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			Integer medianBlockTime = BTC.getInstance().getMedianBlockTime();
			if (medianBlockTime == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_NETWORK_ISSUE);

			long now = NTP.getTime();

			// Check P2SH is funded

			Long p2shBalance = BTC.getInstance().getBalance(p2shAddress.toString());
			if (p2shBalance == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			CrossChainBitcoinP2SHStatus p2shStatus = new CrossChainBitcoinP2SHStatus();
			p2shStatus.bitcoinP2shAddress = p2shAddress.toString();
			p2shStatus.bitcoinP2shBalance = BigDecimal.valueOf(p2shBalance, 8);

			List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress.toString());

			if (p2shBalance >= crossChainTradeData.expectedBitcoin && !fundingOutputs.isEmpty()) {
				p2shStatus.canRedeem = now >= medianBlockTime * 1000L;
				p2shStatus.canRefund = now >= lockTime * 1000L;
			}

			if (now >= medianBlockTime * 1000L) {
				// See if we can extract secret
				List<byte[]> rawTransactions = BTC.getInstance().getAddressTransactions(p2shStatus.bitcoinP2shAddress);
				p2shStatus.secret = BTCP2SH.findP2shSecret(p2shStatus.bitcoinP2shAddress, rawTransactions);
			}

			return p2shStatus;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/p2sh/a/refund")
	@Operation(
		summary = "Returns serialized Bitcoin transaction attempting refund from P2SH-A address",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainBitcoinRefundRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN,
		ApiError.BTC_TOO_SOON, ApiError.BTC_BALANCE_ISSUE, ApiError.BTC_NETWORK_ISSUE, ApiError.REPOSITORY_ISSUE})
	public String refundP2shA(CrossChainBitcoinRefundRequest refundRequest) {
		Security.checkApiCallAllowed(request);

		return refundP2sh(refundRequest, (crossChainTradeData) -> crossChainTradeData.lockTimeA, (crossChainTradeData) -> crossChainTradeData.hashOfSecretA);
	}

	@POST
	@Path("/p2sh/b/refund")
	@Operation(
		summary = "Returns serialized Bitcoin transaction attempting refund from P2SH-B address",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainBitcoinRefundRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN,
		ApiError.BTC_TOO_SOON, ApiError.BTC_BALANCE_ISSUE, ApiError.BTC_NETWORK_ISSUE, ApiError.REPOSITORY_ISSUE})
	public String refundP2shB(CrossChainBitcoinRefundRequest refundRequest) {
		Security.checkApiCallAllowed(request);

		return refundP2sh(refundRequest, (crossChainTradeData) -> crossChainTradeData.lockTimeB, (crossChainTradeData) -> crossChainTradeData.hashOfSecretB);
	}

	private String refundP2sh(CrossChainBitcoinRefundRequest refundRequest, ToIntFunction<CrossChainTradeData> lockTimeFn, Function<CrossChainTradeData, byte[]> hashOfSecretFn) {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		byte[] refundPrivateKey = refundRequest.refundPrivateKey;
		if (refundPrivateKey == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		ECKey refundKey = null;

		try {
			// Auto-trim
			if (refundPrivateKey.length >= 37 && refundPrivateKey.length <= 38)
				refundPrivateKey = Arrays.copyOfRange(refundPrivateKey, 1, 33);
			if (refundPrivateKey.length != 32)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			refundKey = ECKey.fromPrivate(refundPrivateKey);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		}

		if (refundRequest.redeemPublicKeyHash == null || refundRequest.redeemPublicKeyHash.length != 20)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (refundRequest.atAddress == null || !Crypto.isValidAtAddress(refundRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, refundRequest.atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == BTCACCT.Mode.OFFERING || crossChainTradeData.mode == BTCACCT.Mode.CANCELLED)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			int lockTime = lockTimeFn.applyAsInt(crossChainTradeData);
			byte[] hashOfSecret = hashOfSecretFn.apply(crossChainTradeData);

			byte[] redeemScriptBytes = BTCP2SH.buildScript(refundKey.getPubKeyHash(), lockTime, refundRequest.redeemPublicKeyHash, hashOfSecret);
			byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			long now = NTP.getTime();

			// Check P2SH is funded

			Long p2shBalance = BTC.getInstance().getBalance(p2shAddress.toString());
			if (p2shBalance == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress.toString());
			if (fundingOutputs.isEmpty())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			boolean canRefund = now >= lockTime * 1000L;
			if (!canRefund)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_TOO_SOON);

			if (p2shBalance < crossChainTradeData.expectedBitcoin)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_BALANCE_ISSUE);

			Coin refundAmount = Coin.valueOf(p2shBalance - refundRequest.bitcoinMinerFee.unscaledValue().longValue());

			org.bitcoinj.core.Transaction refundTransaction = BTCP2SH.buildRefundTransaction(refundAmount, refundKey, fundingOutputs, redeemScriptBytes, lockTime);
			boolean wasBroadcast = BTC.getInstance().broadcastTransaction(refundTransaction);

			if (!wasBroadcast)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_NETWORK_ISSUE);

			return refundTransaction.getTxId().toString();
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/p2sh/a/redeem")
	@Operation(
		summary = "Returns serialized Bitcoin transaction attempting redeem from P2SH-A address",
		description = "Secret payload needs to be secret-A (64 bytes)",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainBitcoinRedeemRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN,
		ApiError.BTC_TOO_SOON, ApiError.BTC_BALANCE_ISSUE, ApiError.BTC_NETWORK_ISSUE, ApiError.REPOSITORY_ISSUE})
	public String redeemP2shA(CrossChainBitcoinRedeemRequest redeemRequest) {
		Security.checkApiCallAllowed(request);

		return redeemP2sh(redeemRequest, (crossChainTradeData) -> crossChainTradeData.lockTimeA, (crossChainTradeData) -> crossChainTradeData.hashOfSecretA);
	}

	@POST
	@Path("/p2sh/b/redeem")
	@Operation(
		summary = "Returns serialized Bitcoin transaction attempting redeem from P2SH-B address",
		description = "Secret payload needs to be secret-B (32 bytes)",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainBitcoinRedeemRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN,
		ApiError.BTC_TOO_SOON, ApiError.BTC_BALANCE_ISSUE, ApiError.BTC_NETWORK_ISSUE, ApiError.REPOSITORY_ISSUE})
	public String redeemP2shB(CrossChainBitcoinRedeemRequest redeemRequest) {
		Security.checkApiCallAllowed(request);

		return redeemP2sh(redeemRequest, (crossChainTradeData) -> crossChainTradeData.lockTimeB, (crossChainTradeData) -> crossChainTradeData.hashOfSecretB);
	}

	private String redeemP2sh(CrossChainBitcoinRedeemRequest redeemRequest, ToIntFunction<CrossChainTradeData> lockTimeFn, Function<CrossChainTradeData, byte[]> hashOfSecretFn) {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		byte[] redeemPrivateKey = redeemRequest.redeemPrivateKey;
		if (redeemPrivateKey == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		ECKey redeemKey = null;

		try {
			// Auto-trim
			if (redeemPrivateKey.length >= 37 && redeemPrivateKey.length <= 38)
				redeemPrivateKey = Arrays.copyOfRange(redeemPrivateKey, 1, 33);
			if (redeemPrivateKey.length != 32)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			redeemKey = ECKey.fromPrivate(redeemPrivateKey);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		}

		if (redeemRequest.refundPublicKeyHash == null || redeemRequest.refundPublicKeyHash.length != 20)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (redeemRequest.atAddress == null || !Crypto.isValidAtAddress(redeemRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (redeemRequest.secret == null || redeemRequest.secret.length != BTCACCT.SECRET_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (redeemRequest.receivePublicKeyHash == null)
			redeemRequest.receivePublicKeyHash = redeemKey.getPubKeyHash();

		if (redeemRequest.receivePublicKeyHash.length != 20)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, redeemRequest.atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == BTCACCT.Mode.OFFERING || crossChainTradeData.mode == BTCACCT.Mode.CANCELLED)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			int lockTime = lockTimeFn.applyAsInt(crossChainTradeData);
			byte[] hashOfSecret = hashOfSecretFn.apply(crossChainTradeData);

			byte[] redeemScriptBytes = BTCP2SH.buildScript(redeemRequest.refundPublicKeyHash, lockTime, redeemKey.getPubKeyHash(), hashOfSecret);
			byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			Integer medianBlockTime = BTC.getInstance().getMedianBlockTime();
			if (medianBlockTime == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_NETWORK_ISSUE);

			long now = NTP.getTime();

			// Check P2SH is funded
			Long p2shBalance = BTC.getInstance().getBalance(p2shAddress.toString());
			if (p2shBalance == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			if (p2shBalance < crossChainTradeData.expectedBitcoin)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_BALANCE_ISSUE);

			List<TransactionOutput> fundingOutputs = BTC.getInstance().getUnspentOutputs(p2shAddress.toString());
			if (fundingOutputs.isEmpty())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			boolean canRedeem = now >= medianBlockTime * 1000L;
			if (!canRedeem)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_TOO_SOON);

			Coin redeemAmount = Coin.valueOf(p2shBalance - redeemRequest.bitcoinMinerFee.unscaledValue().longValue());

			org.bitcoinj.core.Transaction redeemTransaction = BTCP2SH.buildRedeemTransaction(redeemAmount, redeemKey, fundingOutputs, redeemScriptBytes, redeemRequest.secret, redeemRequest.receivePublicKeyHash);
			boolean wasBroadcast = BTC.getInstance().broadcastTransaction(redeemTransaction);

			if (!wasBroadcast)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_NETWORK_ISSUE);

			return redeemTransaction.getTxId().toString();
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/tradebot")
	@Operation(
		summary = "List current trade-bot states",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TradeBotData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<TradeBotData> getTradeBotStates() {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getCrossChainRepository().getAllTradeBotData();
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/tradebot/create")
	@Operation(
		summary = "Create a trade offer",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = TradeBotCreateRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public String tradeBotCreator(TradeBotCreateRequest tradeBotCreateRequest) {
		Security.checkApiCallAllowed(request);

		Address receiveAddress;
		try {
			receiveAddress = Address.fromString(BTC.getInstance().getNetworkParameters(), tradeBotCreateRequest.receiveAddress);
		} catch (AddressFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		// We only support P2PKH addresses at this time
		if (receiveAddress.getOutputScriptType() != ScriptType.P2PKH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (tradeBotCreateRequest.tradeTimeout < 60)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Do some simple checking first
			Account creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);
			if (creator.getConfirmedBalance(Asset.QORT) < tradeBotCreateRequest.fundingQortAmount)
				throw TransactionsResource.createTransactionInvalidException(request, ValidationResult.NO_BALANCE);

			byte[] unsignedBytes = TradeBot.createTrade(repository, tradeBotCreateRequest);

			return Base58.encode(unsignedBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/tradebot/respond")
	@Operation(
		summary = "Respond to a trade offer (WILL SPEND BITCOIN!)",
		description = "Start a new trade-bot entry to respond to chosen trade offer. Trade-bot starts by funding Bitcoin side of trade!",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = TradeBotRespondRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public String tradeBotResponder(TradeBotRespondRequest tradeBotRespondRequest) {
		Security.checkApiCallAllowed(request);

		final String atAddress = tradeBotRespondRequest.atAddress;

		if (atAddress == null || !Crypto.isValidAtAddress(atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (!BTC.getInstance().isValidXprv(tradeBotRespondRequest.xprv58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		if (tradeBotRespondRequest.receivingAddress == null || !Crypto.isValidAddress(tradeBotRespondRequest.receivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode != BTCACCT.Mode.OFFERING)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			boolean result = TradeBot.startResponse(repository, crossChainTradeData, tradeBotRespondRequest.xprv58, tradeBotRespondRequest.receivingAddress);

			return result ? "true" : "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@DELETE
	@Path("/tradebot/trade")
	@Operation(
		summary = "Delete completed trade",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					example = "Au6kioR6XT2CPxT6qsyQ1WjS9zNYg7tpwSrFeVqCDdMR"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public String tradeBotDelete(String tradePrivateKey58) {
		Security.checkApiCallAllowed(request);

		final byte[] tradePrivateKey;
		try {
			tradePrivateKey = Base58.decode(tradePrivateKey58);

			if (tradePrivateKey.length != 32)
				throw  ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			TradeBotData tradeBotData = repository.getCrossChainRepository().getTradeBotData(tradePrivateKey);
			if (tradeBotData == null)
				return "false";

			switch (tradeBotData.getState()) {
				case BOB_WAITING_FOR_AT_CONFIRM:
				case ALICE_DONE:
				case BOB_DONE:
				case ALICE_REFUNDED:
				case BOB_REFUNDED:
					break;

				default:
					return "false";
			}

			repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
			repository.saveChanges();

			return "true";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	private ATData fetchAtDataWithChecking(Repository repository, String atAddress) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		if (atData == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

		// Must be correct AT - check functionality using code hash
		if (!Arrays.equals(atData.getCodeHash(), BTCACCT.CODE_BYTES_HASH))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// No point sending message to AT that's finished
		if (atData.getIsFinished())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return atData;
	}

	private byte[] buildAtMessage(Repository repository, byte[] senderPublicKey, String atAddress, byte[] messageData) throws DataException {
		// senderPublicKey is actually ephemeral trade public key, so there is no corresponding account and hence no reference
		long txTimestamp = NTP.getTime();

		Random random = new Random();
		byte[] lastReference = new byte[Transformer.SIGNATURE_LENGTH];
		random.nextBytes(lastReference);

		int version = 4;
		int nonce = 0;
		long amount = 0L;
		Long assetId = null; // no assetId as amount is zero
		Long fee = 0L;

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, senderPublicKey, fee, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, version, nonce, atAddress, amount, assetId, messageData, false, false);

		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);

		messageTransaction.computeNonce();

		ValidationResult result = messageTransaction.isValidUnconfirmed();
		if (result != ValidationResult.OK)
			throw TransactionsResource.createTransactionInvalidException(request, result);

		try {
			return MessageTransactionTransformer.toBytes(messageTransactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		}
	}

}