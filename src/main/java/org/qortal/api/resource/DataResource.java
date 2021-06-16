package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.storage.DataFile;
import org.qortal.storage.DataFile.ValidationResult;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Path;
import javax.ws.rs.POST;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;


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
	public String uploadFile(String filePath) {
		Security.checkApiCallAllowed(request);

		// It's too dangerous to allow user-supplied filenames in weaker security contexts
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {

			DataFile dataFile = new DataFile(filePath);
			ValidationResult validationResult = dataFile.isValid();
			if (validationResult != DataFile.ValidationResult.OK) {
				LOGGER.error("Invalid file: {}", validationResult);
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
			}
			LOGGER.info("Whole file digest: {}", dataFile.base58Digest());

			int chunkCount = dataFile.split();
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

}
