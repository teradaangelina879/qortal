package org.qortal.api.apps.resource;

import com.google.common.io.Resources;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.ApiError;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.arbitrary.apps.QApp;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.account.AccountData;
import org.qortal.data.arbitrary.ArbitraryResourceInfo;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.at.ATData;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.group.GroupData;
import org.qortal.data.naming.NameData;
import org.qortal.repository.DataException;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;


@Path("/apps")
@Tag(name = "Apps")
public class AppsResource {

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;

    @GET
    @Path("/q-apps.js")
    @Hidden // For internal Q-App API use only
    @Operation(
            summary = "Javascript interface for Q-Apps",
            responses = {
                    @ApiResponse(
                            description = "javascript",
                            content = @Content(
                                    mediaType = MediaType.TEXT_PLAIN,
                                    schema = @Schema(
                                            type = "string"
                                    )
                            )
                    )
            }
    )
    public String getQAppsJs() {
        URL url = Resources.getResource("q-apps/q-apps.js");
        try {
            return Resources.toString(url, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FILE_NOT_FOUND);
        }
    }

    @GET
    @Path("/q-apps-helper.js")
    @Hidden // For testing only
    public String getQAppsHelperJs() {
        URL url = Resources.getResource("q-apps/q-apps-helper.js");
        try {
            return Resources.toString(url, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FILE_NOT_FOUND);
        }
    }

    @GET
    @Path("/account")
    @Hidden // For internal Q-App API use only
    public AccountData getAccount(@QueryParam("address") String address) {
        try {
            return QApp.getAccountData(address);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/account/names")
    @Hidden // For internal Q-App API use only
    public List<NameData> getAccountNames(@QueryParam("address") String address) {
        try {
            return QApp.getAccountNames(address);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/name")
    @Hidden // For internal Q-App API use only
    public NameData getName(@QueryParam("name") String name) {
        try {
            return QApp.getNameData(name);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/chatmessages")
    @Hidden // For internal Q-App API use only
    public List<ChatMessage> searchChatMessages(@QueryParam("before") Long before, @QueryParam("after") Long after, @QueryParam("txGroupId") Integer txGroupId, @QueryParam("involving") List<String> involvingAddresses, @QueryParam("reference") String reference, @QueryParam("chatReference") String chatReference, @QueryParam("hasChatReference") Boolean hasChatReference, @QueryParam("limit") Integer limit, @QueryParam("offset") Integer offset, @QueryParam("reverse") Boolean reverse) {
        try {
            return QApp.searchChatMessages(before, after, txGroupId, involvingAddresses, reference, chatReference, hasChatReference, limit, offset, reverse);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/resources")
    @Hidden // For internal Q-App API use only
    public List<ArbitraryResourceInfo> getResources(@QueryParam("service") Service service, @QueryParam("identifier") String identifier, @Parameter(description = "Default resources (without identifiers) only") @QueryParam("default") Boolean defaultResource, @Parameter(description = "Filter names by list") @QueryParam("nameListFilter") String nameListFilter, @Parameter(description = "Include status") @QueryParam("includeStatus") Boolean includeStatus, @Parameter(description = "Include metadata") @QueryParam("includeMetadata") Boolean includeMetadata, @Parameter(ref = "limit") @QueryParam("limit") Integer limit, @Parameter(ref = "offset") @QueryParam("offset") Integer offset, @Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
        try {
            return QApp.searchQdnResources(service, identifier, defaultResource, nameListFilter, includeStatus, includeMetadata, limit, offset, reverse);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/resourcestatus")
    @Hidden // For internal Q-App API use only
    public ArbitraryResourceStatus getResourceStatus(@QueryParam("service") Service service, @QueryParam("name") String name, @QueryParam("identifier") String identifier) {
        return QApp.getQdnResourceStatus(service, name, identifier);
    }

    @GET
    @Path("/resource")
    @Hidden // For internal Q-App API use only
    public String getResource(@QueryParam("service") Service service, @QueryParam("name") String name, @QueryParam("identifier") String identifier, @QueryParam("filepath") String filepath, @QueryParam("rebuild") boolean rebuild) {
        try {
            return QApp.fetchQdnResource64(service, name, identifier, filepath, rebuild);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/groups")
    @Hidden // For internal Q-App API use only
    public List<GroupData> listGroups(@Parameter(ref = "limit") @QueryParam("limit") Integer limit, @Parameter(ref = "offset") @QueryParam("offset") Integer offset, @Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
        try {
            return QApp.listGroups(limit, offset, reverse);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/balance")
    @Hidden // For internal Q-App API use only
    public Long getBalance(@QueryParam("assetId") long assetId, @QueryParam("address") String address) {
        try {
            return QApp.getBalance(assetId, address);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/at")
    @Hidden // For internal Q-App API use only
    public ATData getAT(@QueryParam("atAddress") String atAddress) {
        try {
            return QApp.getAtInfo(atAddress);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/atdata")
    @Hidden // For internal Q-App API use only
    public String getATData(@QueryParam("atAddress") String atAddress) {
        try {
            return QApp.getAtData58(atAddress);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

    @GET
    @Path("/ats")
    @Hidden // For internal Q-App API use only
    public List<ATData> listATs(@QueryParam("codeHash58") String codeHash58, @QueryParam("isExecutable") Boolean isExecutable, @Parameter(ref = "limit") @QueryParam("limit") Integer limit, @Parameter(ref = "offset") @QueryParam("offset") Integer offset, @Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
        try {
            return QApp.listATs(codeHash58, isExecutable, limit, offset, reverse);
        } catch (DataException | IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
        }
    }

}
