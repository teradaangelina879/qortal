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

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script.ScriptType;
import org.bitcoinj.wallet.WalletTransaction;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiException;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.model.CrossChainCancelRequest;
import org.qortal.api.model.CrossChainSecretRequest;
import org.qortal.api.model.CrossChainTradeRequest;
import org.qortal.api.model.CrossChainBitcoinP2SHStatus;
import org.qortal.api.model.CrossChainBitcoinRedeemRequest;
import org.qortal.api.model.CrossChainBitcoinRefundRequest;
import org.qortal.api.model.CrossChainBitcoinTemplateRequest;
import org.qortal.api.model.CrossChainBuildRequest;
import org.qortal.asset.Asset;
import org.qortal.crosschain.BTC;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.CrossChainTradeData.Mode;
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
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.DeployAtTransactionTransformer;
import org.qortal.transform.transaction.MessageTransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import com.google.common.primitives.Bytes;

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
		} catch (ApiException e) {
			throw e;
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
		byte[] creatorPublicKey = tradeRequest.creatorPublicKey;

		if (creatorPublicKey == null || creatorPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (tradeRequest.secretHash == null || tradeRequest.secretHash.length != BTC.HASH160_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.tradeTimeout == null)
			tradeRequest.tradeTimeout = 7 * 24 * 60; // 7 days
		else
			if (tradeRequest.tradeTimeout < 10 || tradeRequest.tradeTimeout > 50000)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.initialQortAmount < 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.finalQortAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.fundingQortAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		// funding amount must exceed initial + final
		if (tradeRequest.fundingQortAmount <= tradeRequest.initialQortAmount + tradeRequest.finalQortAmount)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.bitcoinAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PublicKeyAccount creatorAccount = new PublicKeyAccount(repository, creatorPublicKey);

			byte[] creationBytes = BTCACCT.buildQortalAT(creatorAccount.getAddress(), tradeRequest.secretHash, tradeRequest.tradeTimeout, tradeRequest.initialQortAmount, tradeRequest.finalQortAmount, tradeRequest.bitcoinAmount);

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
	@Path("/tradeoffer/recipient")
	@Operation(
		summary = "Builds raw, unsigned MESSAGE transaction that sends cross-chain trade recipient address, triggering 'trade' mode",
		description = "Specify address of cross-chain AT that needs to be messaged, and address of Qortal recipient.<br>"
			+ "AT needs to be in 'offer' mode. Messages sent to an AT in 'trade' mode will be ignored, but still cost fees to send!<br>"
			+ "You need to sign output with same account as the AT creator otherwise the MESSAGE transaction will be invalid.",
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
	public String sendTradeRecipient(CrossChainTradeRequest tradeRequest) {
		byte[] creatorPublicKey = tradeRequest.creatorPublicKey;

		if (creatorPublicKey == null || creatorPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (tradeRequest.atAddress == null || !Crypto.isValidAtAddress(tradeRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (tradeRequest.recipient == null || !Crypto.isValidAddress(tradeRequest.recipient))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, creatorPublicKey, tradeRequest.atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == CrossChainTradeData.Mode.TRADE)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Good to make MESSAGE

			byte[] recipientAddressBytes = Bytes.ensureCapacity(Base58.decode(tradeRequest.recipient), 32, 0);
			byte[] messageTransactionBytes = buildAtMessage(repository, creatorPublicKey, tradeRequest.atAddress, recipientAddressBytes);

			return Base58.encode(messageTransactionBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/tradeoffer/secret")
	@Operation(
		summary = "Builds raw, unsigned MESSAGE transaction that sends secret to AT, releasing funds to recipient",
		description = "Specify address of cross-chain AT that needs to be messaged, and 32-byte secret.<br>"
			+ "AT needs to be in 'trade' mode. Messages sent to an AT in 'trade' mode will be ignored, but still cost fees to send!<br>"
			+ "You need to sign output with account the AT considers the 'recipient' otherwise the MESSAGE transaction will be invalid.",
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
	public String sendSecret(CrossChainSecretRequest secretRequest) {
		byte[] recipientPublicKey = secretRequest.recipientPublicKey;

		if (recipientPublicKey == null || recipientPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (secretRequest.atAddress == null || !Crypto.isValidAtAddress(secretRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (secretRequest.secret == null || secretRequest.secret.length != BTCACCT.SECRET_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, null, secretRequest.atAddress); // null to skip creator check
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == CrossChainTradeData.Mode.OFFER)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			PublicKeyAccount recipientAccount = new PublicKeyAccount(repository, recipientPublicKey);
			String recipientAddress = recipientAccount.getAddress();

			// MESSAGE must come from address that AT considers trade partner / 'recipient'
			if (!crossChainTradeData.qortalRecipient.equals(recipientAddress))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			// Good to make MESSAGE

			byte[] messageTransactionBytes = buildAtMessage(repository, recipientPublicKey, secretRequest.atAddress, secretRequest.secret);

			return Base58.encode(messageTransactionBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@DELETE
	@Path("/tradeoffer")
	@Operation(
		summary = "Builds raw, unsigned MESSAGE transaction that cancels cross-chain trade offer",
		description = "Specify address of cross-chain AT that needs to be cancelled.<br>"
			+ "AT needs to be in 'offer' mode. Messages sent to an AT in 'trade' mode will be ignored, but still cost fees to send!<br>"
			+ "You need to sign output with same account as the AT creator otherwise the MESSAGE transaction will be invalid.",
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
	public String cancelTradeOffer(CrossChainCancelRequest cancelRequest) {
		byte[] creatorPublicKey = cancelRequest.creatorPublicKey;

		if (creatorPublicKey == null || creatorPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (cancelRequest.atAddress == null || !Crypto.isValidAtAddress(cancelRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, creatorPublicKey, cancelRequest.atAddress);
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == CrossChainTradeData.Mode.TRADE)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Good to make MESSAGE

			PublicKeyAccount creatorAccount = new PublicKeyAccount(repository, creatorPublicKey);
			String creatorAddress = creatorAccount.getAddress();
			byte[] recipientAddressBytes = Bytes.ensureCapacity(Base58.decode(creatorAddress), 32, 0);

			byte[] messageTransactionBytes = buildAtMessage(repository, creatorPublicKey, cancelRequest.atAddress, recipientAddressBytes);

			return Base58.encode(messageTransactionBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/p2sh")
	@Operation(
		summary = "Returns Bitcoin P2SH address based on trade info",
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
	public String deriveP2sh(CrossChainBitcoinTemplateRequest templateRequest) {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		Address refundBitcoinAddress = null;
		Address redeemBitcoinAddress = null;

		try {
			if (templateRequest.refundAddress == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

			refundBitcoinAddress = Address.fromString(params, templateRequest.refundAddress);
			if (refundBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		try {
			if (templateRequest.redeemAddress == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

			redeemBitcoinAddress = Address.fromString(params, templateRequest.redeemAddress);
			if (redeemBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		if (templateRequest.atAddress == null || !Crypto.isValidAtAddress(templateRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, null, templateRequest.atAddress); // null to skip creator check
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == Mode.OFFER)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			byte[] redeemScriptBytes = BTCACCT.buildScript(refundBitcoinAddress.getHash(), crossChainTradeData.lockTime, redeemBitcoinAddress.getHash(), crossChainTradeData.secretHash);
			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
			return p2shAddress.toString();
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/p2sh/check")
	@Operation(
		summary = "Checks Bitcoin P2SH address based on trade info",
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
	public CrossChainBitcoinP2SHStatus checkP2sh(CrossChainBitcoinTemplateRequest templateRequest) {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		Address refundBitcoinAddress = null;
		Address redeemBitcoinAddress = null;

		try {
			if (templateRequest.refundAddress == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

			refundBitcoinAddress = Address.fromString(params, templateRequest.refundAddress);
			if (refundBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		try {
			if (templateRequest.redeemAddress == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

			redeemBitcoinAddress = Address.fromString(params, templateRequest.redeemAddress);
			if (redeemBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		if (templateRequest.atAddress == null || !Crypto.isValidAtAddress(templateRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, null, templateRequest.atAddress); // null to skip creator check
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == Mode.OFFER)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			byte[] redeemScriptBytes = BTCACCT.buildScript(refundBitcoinAddress.getHash(), crossChainTradeData.lockTime, redeemBitcoinAddress.getHash(), crossChainTradeData.secretHash);
			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			Long medianBlockTime = BTC.getInstance().getMedianBlockTime();
			if (medianBlockTime == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_NETWORK_ISSUE);

			long now = NTP.getTime();

			// Check P2SH is funded
			final int startTime = (int) (crossChainTradeData.tradeModeTimestamp / 1000L);
			List<TransactionOutput> fundingOutputs = new ArrayList<>();
			List<WalletTransaction> walletTransactions = new ArrayList<>();

			Coin p2shBalance = BTC.getInstance().getBalanceAndOtherInfo(p2shAddress.toString(), startTime, fundingOutputs, walletTransactions);
			if (p2shBalance == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			CrossChainBitcoinP2SHStatus p2shStatus = new CrossChainBitcoinP2SHStatus();
			p2shStatus.bitcoinP2shAddress = p2shAddress.toString();
			p2shStatus.bitcoinP2shBalance = BigDecimal.valueOf(p2shBalance.value, 8);

			if (p2shBalance.value >= crossChainTradeData.expectedBitcoin && fundingOutputs.size() == 1) {
				p2shStatus.canRedeem = now >= medianBlockTime * 1000L;
				p2shStatus.canRefund = now >= crossChainTradeData.lockTime * 1000L;
			}

			if (now >= medianBlockTime * 1000L) {
				// See if we can extract secret
				p2shStatus.secret = BTCACCT.findP2shSecret(p2shStatus.bitcoinP2shAddress, walletTransactions);
			}

			return p2shStatus;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/p2sh/refund")
	@Operation(
		summary = "Returns serialized Bitcoin transaction attempting refund from P2SH address",
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
	public String refundP2sh(CrossChainBitcoinRefundRequest refundRequest) {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		byte[] refundPrivateKey = refundRequest.refundPrivateKey;
		if (refundPrivateKey == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		ECKey refundKey = null;
		Address redeemBitcoinAddress = null;

		try {
			// Auto-trim
			if (refundPrivateKey.length >= 37 && refundPrivateKey.length <= 38)
				refundPrivateKey = Arrays.copyOfRange(refundPrivateKey, 1, 33);
			if (refundPrivateKey.length != 32)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			refundKey = ECKey.fromPrivate(refundPrivateKey);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		try {
			if (refundRequest.redeemAddress == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

			redeemBitcoinAddress = Address.fromString(params, refundRequest.redeemAddress);
			if (redeemBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		if (refundRequest.atAddress == null || !Crypto.isValidAtAddress(refundRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		Address refundAddress = Address.fromKey(params, refundKey, ScriptType.P2PKH);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, null, refundRequest.atAddress); // null to skip creator check
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == Mode.OFFER)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			byte[] redeemScriptBytes = BTCACCT.buildScript(refundAddress.getHash(), crossChainTradeData.lockTime, redeemBitcoinAddress.getHash(), crossChainTradeData.secretHash);
			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			long now = NTP.getTime();

			// Check P2SH is funded
			final int startTime = (int) (crossChainTradeData.tradeModeTimestamp / 1000L);
			List<TransactionOutput> fundingOutputs = new ArrayList<>();

			Coin p2shBalance = BTC.getInstance().getBalanceAndOtherInfo(p2shAddress.toString(), startTime, fundingOutputs, null);
			if (p2shBalance == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			if (fundingOutputs.size() != 1)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			TransactionOutput fundingOutput = fundingOutputs.get(0);
			boolean canRefund = now >= crossChainTradeData.lockTime * 1000L;
			if (!canRefund)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_TOO_SOON);

			if (p2shBalance.value < crossChainTradeData.expectedBitcoin)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_BALANCE_ISSUE);

			Coin refundAmount = p2shBalance.subtract(Coin.valueOf(refundRequest.bitcoinMinerFee.unscaledValue().longValue()));

			org.bitcoinj.core.Transaction refundTransaction = BTCACCT.buildRefundTransaction(refundAmount, refundKey, fundingOutput, redeemScriptBytes, crossChainTradeData.lockTime);
			boolean wasBroadcast = BTC.getInstance().broadcastTransaction(refundTransaction);

			if (!wasBroadcast)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_NETWORK_ISSUE);

			return refundTransaction.getTxId().toString();
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/p2sh/redeem")
	@Operation(
		summary = "Returns serialized Bitcoin transaction attempting redeem from P2SH address",
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
	public String redeemP2sh(CrossChainBitcoinRedeemRequest redeemRequest) {
		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		byte[] redeemPrivateKey = redeemRequest.redeemPrivateKey;
		if (redeemPrivateKey == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		ECKey redeemKey = null;
		Address refundBitcoinAddress = null;

		try {
			// Auto-trim
			if (redeemPrivateKey.length >= 37 && redeemPrivateKey.length <= 38)
				redeemPrivateKey = Arrays.copyOfRange(redeemPrivateKey, 1, 33);
			if (redeemPrivateKey.length != 32)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			redeemKey = ECKey.fromPrivate(redeemPrivateKey);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		try {
			if (redeemRequest.refundAddress == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

			refundBitcoinAddress = Address.fromString(params, redeemRequest.refundAddress);
			if (refundBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		if (redeemRequest.atAddress == null || !Crypto.isValidAtAddress(redeemRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (redeemRequest.secret == null || redeemRequest.secret.length != BTCACCT.SECRET_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		Address redeemAddress = Address.fromKey(params, redeemKey, ScriptType.P2PKH);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, null, redeemRequest.atAddress); // null to skip creator check
			CrossChainTradeData crossChainTradeData = BTCACCT.populateTradeData(repository, atData);

			if (crossChainTradeData.mode == Mode.OFFER)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			byte[] redeemScriptBytes = BTCACCT.buildScript(refundBitcoinAddress.getHash(), crossChainTradeData.lockTime, redeemAddress.getHash(), crossChainTradeData.secretHash);
			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			Long medianBlockTime = BTC.getInstance().getMedianBlockTime();
			if (medianBlockTime == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_NETWORK_ISSUE);

			long now = NTP.getTime();

			// Check P2SH is funded
			final int startTime = (int) (crossChainTradeData.tradeModeTimestamp / 1000L);
			List<TransactionOutput> fundingOutputs = new ArrayList<>();

			Coin p2shBalance = BTC.getInstance().getBalanceAndOtherInfo(p2shAddress.toString(), startTime, fundingOutputs, null);
			if (p2shBalance == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			if (fundingOutputs.size() != 1)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			TransactionOutput fundingOutput = fundingOutputs.get(0);
			boolean canRedeem = now >= medianBlockTime * 1000L;
			if (!canRedeem)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_TOO_SOON);

			if (p2shBalance.value < crossChainTradeData.expectedBitcoin)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_BALANCE_ISSUE);

			Coin redeemAmount = p2shBalance.subtract(Coin.valueOf(redeemRequest.bitcoinMinerFee.unscaledValue().longValue()));

			org.bitcoinj.core.Transaction redeemTransaction = BTCACCT.buildRedeemTransaction(redeemAmount, redeemKey, fundingOutput, redeemScriptBytes, redeemRequest.secret);
			boolean wasBroadcast = BTC.getInstance().broadcastTransaction(redeemTransaction);

			if (!wasBroadcast)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BTC_NETWORK_ISSUE);

			return redeemTransaction.getTxId().toString();
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	private ATData fetchAtDataWithChecking(Repository repository, byte[] creatorPublicKey, String atAddress) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		if (atData == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

		// Does supplied public key match that of AT?
		if (creatorPublicKey != null && !Arrays.equals(creatorPublicKey, atData.getCreatorPublicKey()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		// Must be correct AT - check functionality using code hash
		if (!Arrays.equals(atData.getCodeHash(), BTCACCT.CODE_BYTES_HASH))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// No point sending message to AT that's finished
		if (atData.getIsFinished())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return atData;
	}

	private byte[] buildAtMessage(Repository repository, byte[] senderPublicKey, String atAddress, byte[] messageData) throws DataException {
		PublicKeyAccount creatorAccount = new PublicKeyAccount(repository, senderPublicKey);

		long txTimestamp = NTP.getTime();
		byte[] lastReference = creatorAccount.getLastReference();

		if (lastReference == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_REFERENCE);

		Long fee = null;
		long amount = 0L;
		Long assetId = null; // no assetId as amount is zero

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, senderPublicKey, fee, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, 4, atAddress, amount, assetId, messageData, false, false);

		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);

		fee = messageTransaction.calcRecommendedFee();
		messageTransactionData.setFee(fee);

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