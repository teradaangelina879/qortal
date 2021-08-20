package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.*;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.*;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.DataType;
import org.qortal.group.Group;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.network.message.ArbitraryDataFileMessage;
import org.qortal.network.message.GetArbitraryDataFileMessage;
import org.qortal.network.message.Message;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataFileChunk;
import org.qortal.arbitrary.ArbitraryDataWriter;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.ArbitraryTransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

@Path("/arbitrary")
@Tag(name = "Arbitrary")
public class ArbitraryResource {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryResource.class);

	@Context
	HttpServletRequest request;
	
	@GET
	@Path("/search")
	@Operation(
		summary = "Find matching arbitrary transactions",
		description = "Returns transactions that match criteria. At least either service or address or limit <= 20 must be provided. Block height ranges allowed when searching CONFIRMED transactions ONLY.",
		responses = {
			@ApiResponse(
				description = "transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE
	})
	public List<TransactionData> searchTransactions(@QueryParam("startBlock") Integer startBlock, @QueryParam("blockLimit") Integer blockLimit,
			@QueryParam("txGroupId") Integer txGroupId,
			@QueryParam("service") Integer service, @QueryParam("address") String address, @Parameter(
				description = "whether to include confirmed, unconfirmed or both",
				required = true
			) @QueryParam("confirmationStatus") ConfirmationStatus confirmationStatus, @Parameter(
				ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		// Must have at least one of txType / address / limit <= 20
		if (service == null && (address == null || address.isEmpty()) && (limit == null || limit > 20))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// You can't ask for unconfirmed and impose a block height range
		if (confirmationStatus != ConfirmationStatus.CONFIRMED && (startBlock != null || blockLimit != null))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		List<TransactionType> txTypes = new ArrayList<>();
		txTypes.add(TransactionType.ARBITRARY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(startBlock, blockLimit, txGroupId, txTypes, 
					service, address, confirmationStatus, limit, offset, reverse);

			// Expand signatures to transactions
			List<TransactionData> transactions = new ArrayList<TransactionData>(signatures.size());
			for (byte[] signature : signatures)
				transactions.add(repository.getTransactionRepository().fromSignature(signature));

			return transactions;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/raw/{signature}")
	@Operation(
		summary = "Fetch raw data associated with passed transaction signature",
		responses = {
			@ApiResponse(
				description = "raw data",
				content = @Content(
					schema = @Schema(type = "string", format = "byte"),
					mediaType = MediaType.APPLICATION_OCTET_STREAM
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_SIGNATURE, ApiError.REPOSITORY_ISSUE, ApiError.TRANSACTION_INVALID
	})
	public byte[] fetchRawData(@PathParam("signature") String signature58) {
		// Decode signature
		byte[] signature;
		try {
			signature = Base58.decode(signature58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_SIGNATURE, e);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);

			if (transactionData == null || transactionData.getType() != TransactionType.ARBITRARY) 
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_SIGNATURE);

			ArbitraryTransactionData arbitraryTxData = (ArbitraryTransactionData) transactionData;

			// We're really expecting to only fetch the data's hash from repository
			if (arbitraryTxData.getDataType() != DataType.DATA_HASH)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSACTION_INVALID);

			ArbitraryTransaction arbitraryTx = new ArbitraryTransaction(repository, arbitraryTxData);

			// For now, we only allow locally stored data
			if (!arbitraryTx.isDataLocal())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSACTION_INVALID);

			return arbitraryTx.fetchData();
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Operation(
		summary = "Build raw, unsigned, ARBITRARY transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = ArbitraryTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.INVALID_DATA, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String createArbitrary(ArbitraryTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		if (transactionData.getDataType() == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = ArbitraryTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/upload/creator/{publickey}")
	@Operation(
			summary = "Build raw, unsigned, ARBITRARY transaction, based on a user-supplied path to a single file",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string", example = "qortal.jar"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public String uploadFileAtPath(@PathParam("publickey") String creatorPublicKeyBase58, String path) {
		Security.checkApiCallAllowed(request);

		// It's too dangerous to allow user-supplied filenames in weaker security contexts
		if (Settings.getInstance().isApiRestricted()) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);
		}

		ArbitraryDataFile arbitraryDataFile = null;
		try (final Repository repository = RepositoryManager.getRepository()) {

			if (creatorPublicKeyBase58 == null || path == null) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}
			byte[] creatorPublicKey = Base58.decode(creatorPublicKeyBase58);
			final String creatorAddress = Crypto.toAddress(creatorPublicKey);
			byte[] lastReference = repository.getAccountRepository().getLastReference(creatorAddress);
			if (lastReference == null) {
				// Use a random last reference on the very first transaction for an account
				// Code copied from CrossChainResource.buildAtMessage()
				// We already require PoW on all arbitrary transactions, so no additional logic is needed
				Random random = new Random();
				lastReference = new byte[Transformer.SIGNATURE_LENGTH];
				random.nextBytes(lastReference);
			}

			String name = null;
			byte[] secret = null;
			Method method = Method.PUT;
			Service service = Service.ARBITRARY_DATA;
			Compression compression = Compression.NONE;

			ArbitraryDataWriter arbitraryDataWriter = new ArbitraryDataWriter(Paths.get(path), name, service, method, compression);
			try {
				arbitraryDataWriter.save();
			} catch (IOException | DataException | InterruptedException e) {
				LOGGER.info("Unable to create arbitrary data file: {}", e.getMessage());
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE);
			} catch (RuntimeException e) {
				LOGGER.info("Unable to create arbitrary data file: {}", e.getMessage());
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
			}

			arbitraryDataFile = arbitraryDataWriter.getArbitraryDataFile();
			if (arbitraryDataFile == null) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
			}

			ArbitraryDataFile.ValidationResult validationResult = arbitraryDataFile.isValid();
			if (validationResult != ArbitraryDataFile.ValidationResult.OK) {
				LOGGER.error("Invalid file: {}", validationResult);
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
			}
			LOGGER.info("Whole file digest: {}", arbitraryDataFile.digest58());

			int chunkCount = arbitraryDataFile.split(ArbitraryDataFile.CHUNK_SIZE);
			if (chunkCount == 0) {
				LOGGER.error("No chunks created");
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
			}
			LOGGER.info(String.format("Successfully split into %d chunk%s", chunkCount, (chunkCount == 1 ? "" : "s")));

			String digest58 = arbitraryDataFile.digest58();
			if (digest58 == null) {
				LOGGER.error("Unable to calculate digest");
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
			}

			final BaseTransactionData baseTransactionData = new BaseTransactionData(NTP.getTime(), Group.NO_GROUP,
					lastReference, creatorPublicKey, BlockChain.getInstance().getUnitFee(), null);
			final int size = (int) arbitraryDataFile.size();
			final int version = 5;
			final int nonce = 0;
			final ArbitraryTransactionData.DataType dataType = ArbitraryTransactionData.DataType.DATA_HASH;
			final byte[] digest = arbitraryDataFile.digest();
			final byte[] chunkHashes = arbitraryDataFile.chunkHashes();
			final List<PaymentData> payments = new ArrayList<>();

			ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
					version, service, nonce, size, name, method,
					secret, compression, digest, dataType, chunkHashes, payments);

			ArbitraryTransaction transaction = (ArbitraryTransaction) Transaction.fromData(repository, transactionData);
			transaction.computeNonce();

			Transaction.ValidationResult result = transaction.isValidUnconfirmed();
			if (result != Transaction.ValidationResult.OK) {
				arbitraryDataFile.deleteAll();
				throw TransactionsResource.createTransactionInvalidException(request, result);
			}

			byte[] bytes = ArbitraryTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);

		} catch (DataException e) {
			arbitraryDataFile.deleteAll();
			LOGGER.error("Repository issue when uploading data", e);
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (TransformationException e) {
			arbitraryDataFile.deleteAll();
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (IllegalStateException e) {
			arbitraryDataFile.deleteAll();
			LOGGER.error("Invalid upload data", e);
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA, e);
		}
	}


	@DELETE
	@Path("/file")
	@Operation(
			summary = "Delete file using supplied base58 encoded SHA256 digest string",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string", example = "FZdHKgF5CbN2tKihvop5Ts9vmWmA9ZyyPY6bC1zivjy4"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "true if deleted, false if not",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	public String deleteFile(String hash58) {
		Security.checkApiCallAllowed(request);

		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash58(hash58);
		if (arbitraryDataFile.delete()) {
			return "true";
		}
		return "false";
	}

	@GET
	@Path("/file/{hash}/frompeer/{peer}")
	@Operation(
			summary = "Request file from a given peer, using supplied base58 encoded SHA256 hash",
			responses = {
					@ApiResponse(
							description = "true if retrieved, false if not",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND, ApiError.NO_REPLY})
	public Response getFileFromPeer(@PathParam("hash") String hash58,
									@PathParam("peer") String targetPeerAddress) {
		try {
			if (hash58 == null) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}
			if (targetPeerAddress == null) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}

			// Try to resolve passed address to make things easier
			PeerAddress peerAddress = PeerAddress.fromString(targetPeerAddress);
			InetSocketAddress resolvedAddress = peerAddress.toSocketAddress();
			List<Peer> peers = Network.getInstance().getHandshakedPeers();
			Peer targetPeer = peers.stream().filter(peer -> peer.getResolvedAddress().toString().contains(resolvedAddress.toString())).findFirst().orElse(null);

			if (targetPeer == null) {
				LOGGER.info("Peer {} isn't connected", targetPeerAddress);
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}

			boolean success = this.requestFile(hash58, targetPeer);
			if (success) {
				return Response.ok("true").build();
			}
			return Response.ok("false").build();

		} catch (UnknownHostException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
	}

	@POST
	@Path("/files/frompeer/{peer}")
	@Operation(
			summary = "Request multiple files from a given peer, using supplied comma separated base58 encoded SHA256 hashes",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string", example = "FZdHKgF5CbN2tKihvop5Ts9vmWmA9ZyyPY6bC1zivjy4,FZdHKgF5CbN2tKihvop5Ts9vmWmA9ZyyPY6bC1zivjy4"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "true if retrieved, false if not",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND, ApiError.NO_REPLY})
	public Response getFilesFromPeer(String files, @PathParam("peer") String targetPeerAddress) {
		try {
			if (targetPeerAddress == null) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}

			// Try to resolve passed address to make things easier
			PeerAddress peerAddress = PeerAddress.fromString(targetPeerAddress);
			InetSocketAddress resolvedAddress = peerAddress.toSocketAddress();
			List<Peer> peers = Network.getInstance().getHandshakedPeers();
			Peer targetPeer = peers.stream().filter(peer -> peer.getResolvedAddress().toString().contains(resolvedAddress.toString())).findFirst().orElse(null);

			for (Peer peer : peers) {
				LOGGER.info("peer: {}", peer);
			}

			if (targetPeer == null) {
				LOGGER.info("Peer {} isn't connected", targetPeerAddress);
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}

			String hash58List[] = files.split(",");
			for (String hash58 : hash58List) {
				if (hash58 != null) {
					boolean success = this.requestFile(hash58, targetPeer);
					if (!success) {
						LOGGER.info("Failed to request file {} from peer {}", hash58, targetPeerAddress);
					}
				}
			}
			return Response.ok("true").build();

		} catch (UnknownHostException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
	}


	private boolean requestFile(String hash58, Peer targetPeer) {
		try (final Repository repository = RepositoryManager.getRepository()) {

			ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash58(hash58);
			if (arbitraryDataFile.exists()) {
				LOGGER.info("Data file {} already exists but we'll request it anyway", arbitraryDataFile);
			}

			byte[] digest = null;
			try {
				digest = Base58.decode(hash58);
			} catch (NumberFormatException e) {
				LOGGER.info("Invalid base58 encoded string");
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
			}
			Message getArbitraryDataFileMessage = new GetArbitraryDataFileMessage(digest);

			Message message = targetPeer.getResponse(getArbitraryDataFileMessage);
			if (message == null) {
				return false;
			}
			else if (message.getType() == Message.MessageType.BLOCK_SUMMARIES) { // TODO: use dedicated message type here
				return false;
			}

			ArbitraryDataFileMessage arbitraryDataFileMessage = (ArbitraryDataFileMessage) message;
			arbitraryDataFile = arbitraryDataFileMessage.getArbitraryDataFile();
			if (arbitraryDataFile == null || !arbitraryDataFile.exists()) {
				return false;
			}
			LOGGER.info(String.format("Received file %s, size %d bytes", arbitraryDataFileMessage.getArbitraryDataFile(), arbitraryDataFileMessage.getArbitraryDataFile().size()));
			return true;
		} catch (ApiException e) {
			throw e;
		} catch (DataException | InterruptedException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/file/{hash}/build")
	@Operation(
			summary = "Join multiple chunks into a single file, using supplied comma separated base58 encoded SHA256 digest strings",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string", example = "FZdHKgF5CbN2tKihvop5Ts9vmWmA9ZyyPY6bC1zivjy4,FZdHKgF5CbN2tKihvop5Ts9vmWmA9ZyyPY6bC1zivjy4"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "true if joined, false if not",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND, ApiError.NO_REPLY})
	public Response joinFiles(String files, @PathParam("hash") String combinedHash) {

		if (combinedHash == null) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash58(combinedHash);
		if (arbitraryDataFile.exists()) {
			LOGGER.info("We already have the combined file {}, but we'll join the chunks anyway.", combinedHash);
		}

		String hash58List[] = files.split(",");
		for (String hash58 : hash58List) {
			if (hash58 != null) {
				ArbitraryDataFileChunk chunk = ArbitraryDataFileChunk.fromHash58(hash58);
				arbitraryDataFile.addChunk(chunk);
			}
		}
		boolean success = arbitraryDataFile.join();
		if (success) {
			if (combinedHash.equals(arbitraryDataFile.digest58())) {
				LOGGER.info("Valid hash {} after joining {} files", arbitraryDataFile.digest58(), arbitraryDataFile.chunkCount());
				return Response.ok("true").build();
			}
		}

		return Response.ok("false").build();
	}

}
