package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.ChatTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.ChatTransactionTransformer;
import org.qortal.utils.Base58;

@Path("/chat")
@Tag(name = "Chat")
public class ChatResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/search")
	@Operation(
		summary = "Find chat messages",
		description = "Returns CHAT transactions that match criteria.",
		responses = {
			@ApiResponse(
				description = "transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = ChatTransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<ChatTransactionData> searchChat(@QueryParam("before") Long before, @QueryParam("after") Long after,
			@QueryParam("txGroupId") Integer txGroupId,
			@QueryParam("sender") String senderAddress,
			@QueryParam("recipient") String recipientAddress,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		// Check any provided addresses are valid
		if (senderAddress != null && !Crypto.isValidAddress(senderAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (recipientAddress != null && !Crypto.isValidAddress(recipientAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (before != null && before < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (after != null && after < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getChatRepository().getTransactionsMatchingCriteria(
					before,
					after,
					txGroupId,
					senderAddress,
					recipientAddress,
					limit, offset, reverse);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Operation(
		summary = "Build raw, unsigned, CHAT transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = ChatTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CHAT transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String buildChat(ChatTransactionData transactionData) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChatTransaction chatTransaction = (ChatTransaction) Transaction.fromData(repository, transactionData);

			// Quicker validity check first before we compute nonce
			ValidationResult result = chatTransaction.isValid();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			chatTransaction.computeNonce();

			result = chatTransaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = ChatTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}