package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.*;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.network.message.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.storage.DataFile;
import org.qortal.storage.DataFile.ValidationResult;
import org.qortal.storage.DataFileChunk;
import org.qortal.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;


@Path("/data")
@Tag(name = "Data")
public class DataResource {

	private static final Logger LOGGER = LogManager.getLogger(DataResource.class);

	@Context
	HttpServletRequest request;

	@POST
	@Path("/upload/path")
	@Operation(
		summary = "Build raw, unsigned, UPLOAD_DATA transaction, based on a user-supplied file path",
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
				description = "raw, unsigned, UPLOAD_DATA transaction encoded in Base58",
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
	public String uploadFileAtPath(String path) {
		Security.checkApiCallAllowed(request);

		// It's too dangerous to allow user-supplied filenames in weaker security contexts
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {

			// Check if a file or directory has been supplied
			File file = new File(path);
			if (!file.isFile()) {
				LOGGER.info("Not a file: {}", path);
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}

			DataFile dataFile = new DataFile(path);
			ValidationResult validationResult = dataFile.isValid();
			if (validationResult != DataFile.ValidationResult.OK) {
				LOGGER.error("Invalid file: {}", validationResult);
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
			}
			LOGGER.info("Whole file digest: {}", dataFile.base58Digest());

			int chunkCount = dataFile.split(DataFile.CHUNK_SIZE);
			if (chunkCount > 0) {
				LOGGER.info(String.format("Successfully split into %d chunk%s", chunkCount, (chunkCount == 1 ? "" : "s")));
				return "true";
			}

			return "false";

		} catch (DataException e) {
			LOGGER.error("Repository issue when uploading data", e);
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (IllegalStateException e) {
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
	public String deleteFile(String base58Digest) {
		Security.checkApiCallAllowed(request);

		DataFile dataFile = DataFile.fromBase58Digest(base58Digest);
		if (dataFile.delete()) {
			return "true";
		}
		return "false";
	}

	@GET
	@Path("/file/{hash}/frompeer/{peer}")
	@Operation(
			summary = "Request file from a given peer, using supplied base58 encoded SHA256 digest string",
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
	public Response getFileFromPeer(@PathParam("hash") String base58Digest,
									@PathParam("peer") String targetPeerAddress) {
		try {
			if (base58Digest == null) {
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

			boolean success = this.requestFile(base58Digest, targetPeer);
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
			summary = "Request multiple files from a given peer, using supplied comma separated base58 encoded SHA256 digest strings",
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

			String base58DigestList[] = files.split(",");
			for (String base58Digest : base58DigestList) {
				if (base58Digest != null) {
					boolean success = this.requestFile(base58Digest, targetPeer);
					if (!success) {
						LOGGER.info("Failed to request file {} from peer {}", base58Digest, targetPeerAddress);
					}
				}
			}
			return Response.ok("true").build();

		} catch (UnknownHostException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
	}


	private boolean requestFile(String base58Digest, Peer targetPeer) {
		try (final Repository repository = RepositoryManager.getRepository()) {

			DataFile dataFile = DataFile.fromBase58Digest(base58Digest);
			if (dataFile.exists()) {
				LOGGER.info("Data file {} already exists but we'll request it anyway", dataFile);
			}

			byte[] digest = null;
			try {
				digest = Base58.decode(base58Digest);
			} catch (NumberFormatException e) {
				LOGGER.info("Invalid base58 encoded string");
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
			}
			Message getDataFileMessage = new GetDataFileMessage(digest);

			Message message = targetPeer.getResponse(getDataFileMessage);
			if (message == null) {
				return false;
			}
			else if (message.getType() == Message.MessageType.BLOCK_SUMMARIES) { // TODO: use dedicated message type here
				return false;
			}

			DataFileMessage dataFileMessage = (DataFileMessage) message;
			dataFile = dataFileMessage.getDataFile();
			if (dataFile == null || !dataFile.exists()) {
				return false;
			}
			LOGGER.info(String.format("Received file %s, size %d bytes", dataFileMessage.getDataFile(), dataFileMessage.getDataFile().size()));
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
	public Response joinFiles(String files, @PathParam("combinedHash") String combinedHash) {

		if (combinedHash == null) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		DataFile dataFile = DataFile.fromBase58Digest(combinedHash);
		if (dataFile.exists()) {
			LOGGER.info("We already have the combined file {}, but we'll join the chunks anyway.", combinedHash);
		}

		String base58DigestList[] = files.split(",");
		for (String base58Digest : base58DigestList) {
			if (base58Digest != null) {
				DataFileChunk chunk = DataFileChunk.fromBase58Digest(base58Digest);
				dataFile.addChunk(chunk);
			}
		}
		boolean success = dataFile.join();
		if (success) {
			if (combinedHash.equals(dataFile.base58Digest())) {
				LOGGER.info("Valid hash {} after joining {} files", dataFile.base58Digest(), dataFile.chunkCount());
				return Response.ok("true").build();
			}
		}

		return Response.ok("false").build();
	}
}
