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

import org.ciyam.at.MachineState;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiException;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.model.CrossChainCancelRequest;
import org.qortal.api.model.CrossChainSecretRequest;
import org.qortal.api.model.CrossChainTradeRequest;
import org.qortal.api.model.CrossChainBuildRequest;
import org.qortal.asset.Asset;
import org.qortal.at.QortalAtLoggerFactory;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
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
	@ApiErrors({
		ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE
	})
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
				String atAddress = atData.getATAddress();

				ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

				QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();
				byte[] dataBytes = MachineState.extractDataBytes(loggerFactory, atStateData.getStateData());

				CrossChainTradeData crossChainTradeData = new CrossChainTradeData();
				crossChainTradeData.qortalAddress = atAddress;
				crossChainTradeData.qortalCreator = Crypto.toAddress(atData.getCreatorPublicKey());
				crossChainTradeData.creationTimestamp = atData.getCreation();
				crossChainTradeData.qortBalance = repository.getAccountRepository().getBalance(atAddress, Asset.QORT).getBalance();

				BTCACCT.populateTradeData(crossChainTradeData, dataBytes);

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
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.REPOSITORY_ISSUE})
	public String buildTrade(CrossChainBuildRequest tradeRequest) {
		byte[] creatorPublicKey = tradeRequest.creatorPublicKey;

		if (creatorPublicKey == null || creatorPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (tradeRequest.secretHash == null || tradeRequest.secretHash.length != 20)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.tradeTimeout == null)
			tradeRequest.tradeTimeout = 7 * 24 * 60; // 7 days
		else
			if (tradeRequest.tradeTimeout < 10 || tradeRequest.tradeTimeout > 50000)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.initialQortAmount == null || tradeRequest.initialQortAmount.signum() < 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.finalQortAmount == null || tradeRequest.finalQortAmount.signum() <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.fundingQortAmount == null || tradeRequest.fundingQortAmount.signum() <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		// funding amount must exceed initial + final
		if (tradeRequest.fundingQortAmount.compareTo(tradeRequest.initialQortAmount.add(tradeRequest.finalQortAmount)) <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (tradeRequest.bitcoinAmount == null || tradeRequest.bitcoinAmount.signum() <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PublicKeyAccount creatorAccount = new PublicKeyAccount(repository, creatorPublicKey);

			byte[] creationBytes = BTCACCT.buildQortalAT(creatorAccount.getAddress(), tradeRequest.secretHash, tradeRequest.tradeTimeout, tradeRequest.initialQortAmount, tradeRequest.finalQortAmount, tradeRequest.bitcoinAmount);

			long txTimestamp = NTP.getTime();
			byte[] lastReference = creatorAccount.getLastReference();
			if (lastReference == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_REFERENCE);

			BigDecimal fee = BigDecimal.ZERO;
			String name = "QORT-BTC cross-chain trade";
			String description = String.format("Qortal-Bitcoin cross-chain trade");
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
	@ApiErrors({
		ApiError.REPOSITORY_ISSUE
	})
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

			// Determine state of AT
			ATStateData atStateData = repository.getATRepository().getLatestATState(tradeRequest.atAddress);

			QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();
			byte[] dataBytes = MachineState.extractDataBytes(loggerFactory, atStateData.getStateData());

			CrossChainTradeData crossChainTradeData = new CrossChainTradeData();
			BTCACCT.populateTradeData(crossChainTradeData, dataBytes);

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
	@ApiErrors({
		ApiError.REPOSITORY_ISSUE
	})
	public String sendSecret(CrossChainSecretRequest secretRequest) {
		byte[] recipientPublicKey = secretRequest.recipientPublicKey;

		if (recipientPublicKey == null || recipientPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (secretRequest.atAddress == null || !Crypto.isValidAtAddress(secretRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (secretRequest.secret == null || secretRequest.secret.length != 32)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, null, secretRequest.atAddress); // null to skip creator check

			// Determine state of AT
			ATStateData atStateData = repository.getATRepository().getLatestATState(secretRequest.atAddress);

			QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();
			byte[] dataBytes = MachineState.extractDataBytes(loggerFactory, atStateData.getStateData());

			CrossChainTradeData crossChainTradeData = new CrossChainTradeData();
			BTCACCT.populateTradeData(crossChainTradeData, dataBytes);

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
	@ApiErrors({
		ApiError.REPOSITORY_ISSUE
	})
	public String cancelTradeOffer(CrossChainCancelRequest cancelRequest) {
		byte[] creatorPublicKey = cancelRequest.creatorPublicKey;

		if (creatorPublicKey == null || creatorPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (cancelRequest.atAddress == null || !Crypto.isValidAtAddress(cancelRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, creatorPublicKey, cancelRequest.atAddress);

			// Determine state of AT
			ATStateData atStateData = repository.getATRepository().getLatestATState(cancelRequest.atAddress);

			QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();
			byte[] dataBytes = MachineState.extractDataBytes(loggerFactory, atStateData.getStateData());

			CrossChainTradeData crossChainTradeData = new CrossChainTradeData();
			BTCACCT.populateTradeData(crossChainTradeData, dataBytes);

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

		BigDecimal fee = BigDecimal.ZERO;
		BigDecimal amount = BigDecimal.ZERO;

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, senderPublicKey, fee, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, 4, atAddress, Asset.QORT, amount, messageData, false, false);

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