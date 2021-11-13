package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.qortal.api.*;
import org.qortal.api.model.ListRequest;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.list.ResourceListManager;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;


@Path("/lists")
@Tag(name = "Lists")
public class ListsResource {

	@Context
	HttpServletRequest request;


	@POST
	@Path("/{category}/{resourceName}")
	@Operation(
			summary = "Add items to a new or existing list",
			description = "Example categories are 'blacklist' or 'followed'. Example resource names are 'addresses' or 'names'",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ListRequest.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "Returns true if all items were processed, false if any couldn't be " +
									"processed, or an exception on failure. If false or an exception is returned, " +
									"the list will not be updated, and the request will need to be re-issued.",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public String addItemstoList(@PathParam("category") String category,
								 @PathParam("resourceName") String resourceName,
								 ListRequest listRequest) {
		Security.checkApiCallAllowed(request);

		if (category == null || resourceName == null) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		if (listRequest == null || listRequest.items == null) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		int successCount = 0;
		int errorCount = 0;

		try (final Repository repository = RepositoryManager.getRepository()) {

			for (String item : listRequest.items) {

				// Validate addresses
				if (resourceName.equals("address") || resourceName.equals("addresses")) {

					if (!Crypto.isValidAddress(item)) {
						errorCount++;
						continue;
					}

					AccountData accountData = repository.getAccountRepository().getAccount(item);
					// Not found?
					if (accountData == null) {
						errorCount++;
						continue;
					}
				}

				// Validate names
				if (resourceName.equals("name") || resourceName.equals("names")) {
					if (!repository.getNameRepository().nameExists(item)) {
						errorCount++;
						continue;
					}
				}

				// Valid address, so go ahead and blacklist it
				boolean success = ResourceListManager.getInstance().addToList(category, resourceName, item, false);
				if (success) {
					successCount++;
				}
				else {
					errorCount++;
				}
			}
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		if (successCount > 0 && errorCount == 0) {
			// All were successful, so save the blacklist
			ResourceListManager.getInstance().saveList(category, resourceName);
			return "true";
		}
		else {
			// Something went wrong, so revert
			ResourceListManager.getInstance().revertList(category, resourceName);
			return "false";
		}
	}

	@DELETE
	@Path("/{category}/{resourceName}")
	@Operation(
			summary = "Remove one or more items from a list",
			description = "Example categories are 'blacklist' or 'followed'. Example resource names are 'addresses' or 'names'",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ListRequest.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "Returns true if all items were processed, false if any couldn't be " +
									"processed, or an exception on failure. If false or an exception is returned, " +
									"the list will not be updated, and the request will need to be re-issued.",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public String removeItemsFromList(@PathParam("category") String category,
									  @PathParam("resourceName") String resourceName,
									  ListRequest listRequest) {
		Security.checkApiCallAllowed(request);

		if (listRequest == null || listRequest.items == null) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		int successCount = 0;
		int errorCount = 0;

		for (String address : listRequest.items) {

			// Attempt to remove the item
			// Don't save as we will do this at the end of the process
			boolean success = ResourceListManager.getInstance().removeFromList(category, resourceName, address, false);
			if (success) {
				successCount++;
			}
			else {
				errorCount++;
			}
		}

		if (successCount > 0 && errorCount == 0) {
			// All were successful, so save the blacklist
			ResourceListManager.getInstance().saveList(category, resourceName);
			return "true";
		}
		else {
			// Something went wrong, so revert
			ResourceListManager.getInstance().revertList(category, resourceName);
			return "false";
		}
	}

	@GET
	@Path("/{category}/{resourceName}")
	@Operation(
			summary = "Fetch all items in a list",
			description = "Example categories are 'blacklist' or 'followed'. Example resource names are 'addresses' or 'names'",
			responses = {
					@ApiResponse(
							description = "A JSON array of items",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = String.class)))
					)
			}
	)
	public String getItemsInList(@PathParam("category") String category,
								 @PathParam("resourceName") String resourceName) {
		Security.checkApiCallAllowed(request);
		return ResourceListManager.getInstance().getJSONStringForList(category, resourceName);
	}

}
