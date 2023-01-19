package org.qortal.api.apps.resource;

import com.google.common.io.Resources;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.*;
import org.qortal.api.model.NameSummary;
import org.qortal.api.resource.*;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.account.AccountData;
import org.qortal.data.arbitrary.ArbitraryResourceInfo;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.at.ATData;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.group.GroupData;
import org.qortal.data.naming.NameData;
import org.qortal.utils.Base58;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
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
        AddressesResource addressesResource = (AddressesResource) buildResource(AddressesResource.class, request, response, context);
        return addressesResource.getAccountInfo(address);
    }

    @GET
    @Path("/account/names")
    @Hidden // For internal Q-App API use only
    public List<NameSummary> getAccountNames(@QueryParam("address") String address) {
        NamesResource namesResource = (NamesResource) buildResource(NamesResource.class, request, response, context);
        return namesResource.getNamesByAddress(address, 0, 0 ,false);
    }

    @GET
    @Path("/name")
    @Hidden // For internal Q-App API use only
    public NameData getName(@QueryParam("name") String name) {
        NamesResource namesResource = (NamesResource) buildResource(NamesResource.class, request, response, context);
        return namesResource.getName(name);
    }

    @GET
    @Path("/chatmessages")
    @Hidden // For internal Q-App API use only
    public List<ChatMessage> searchChatMessages(@QueryParam("before") Long before, @QueryParam("after") Long after, @QueryParam("txGroupId") Integer txGroupId, @QueryParam("involving") List<String> involvingAddresses, @QueryParam("reference") String reference, @QueryParam("chatReference") String chatReference, @QueryParam("hasChatReference") Boolean hasChatReference, @QueryParam("limit") Integer limit, @QueryParam("offset") Integer offset, @QueryParam("reverse") Boolean reverse) {
        ChatResource chatResource = (ChatResource) buildResource(ChatResource.class, request, response, context);
        return chatResource.searchChat(before, after, txGroupId, involvingAddresses, reference, chatReference, hasChatReference, limit, offset, reverse);
    }

    @GET
    @Path("/resources")
    @Hidden // For internal Q-App API use only
    public List<ArbitraryResourceInfo> getResources(@QueryParam("service") Service service, @QueryParam("identifier") String identifier, @Parameter(description = "Default resources (without identifiers) only") @QueryParam("default") Boolean defaultResource, @Parameter(description = "Filter names by list") @QueryParam("nameListFilter") String nameListFilter, @Parameter(description = "Include status") @QueryParam("includeStatus") Boolean includeStatus, @Parameter(description = "Include metadata") @QueryParam("includeMetadata") Boolean includeMetadata, @Parameter(ref = "limit") @QueryParam("limit") Integer limit, @Parameter(ref = "offset") @QueryParam("offset") Integer offset, @Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
        ArbitraryResource arbitraryResource = (ArbitraryResource) buildResource(ArbitraryResource.class, request, response, context);
        return arbitraryResource.getResources(service, identifier, defaultResource, limit, offset, reverse, nameListFilter, includeStatus, includeMetadata);
    }

    @GET
    @Path("/resourcestatus")
    @Hidden // For internal Q-App API use only
    public ArbitraryResourceStatus getResourceStatus(@QueryParam("service") Service service, @QueryParam("name") String name, @QueryParam("identifier") String identifier) {
        ArbitraryResource arbitraryResource = (ArbitraryResource) buildResource(ArbitraryResource.class, request, response, context);
        ApiKey apiKey = ApiService.getInstance().getApiKey();
        return arbitraryResource.getResourceStatus(apiKey.toString(), service, name, identifier, false);
    }

    @GET
    @Path("/resource")
    @Hidden // For internal Q-App API use only
    public HttpServletResponse getResource(@QueryParam("service") Service service, @QueryParam("name") String name, @QueryParam("identifier") String identifier, @QueryParam("filepath") String filepath, @QueryParam("rebuild") boolean rebuild) {
        ArbitraryResource arbitraryResource = (ArbitraryResource) buildResource(ArbitraryResource.class, request, response, context);
        ApiKey apiKey = ApiService.getInstance().getApiKey();
        return arbitraryResource.get(apiKey.toString(), service, name, identifier, filepath, rebuild, false, 5);
    }

    @GET
    @Path("/groups")
    @Hidden // For internal Q-App API use only
    public List<GroupData> listGroups(@Parameter(ref = "limit") @QueryParam("limit") Integer limit, @Parameter(ref = "offset") @QueryParam("offset") Integer offset, @Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
        GroupsResource groupsResource = (GroupsResource) buildResource(GroupsResource.class, request, response, context);
        return groupsResource.getAllGroups(limit, offset, reverse);
    }

    @GET
    @Path("/balance")
    @Hidden // For internal Q-App API use only
    public BigDecimal getBalance(@QueryParam("assetId") long assetId, @QueryParam("address") String address) {
        AddressesResource addressesResource = (AddressesResource) buildResource(AddressesResource.class, request, response, context);
        return addressesResource.getBalance(address, assetId);
    }

    @GET
    @Path("/at")
    @Hidden // For internal Q-App API use only
    public ATData getAT(@QueryParam("atAddress") String atAddress) {
        AtResource atResource = (AtResource) buildResource(AtResource.class, request, response, context);
        return atResource.getByAddress(atAddress);
    }

    @GET
    @Path("/atdata")
    @Hidden // For internal Q-App API use only
    public String getATData(@QueryParam("atAddress") String atAddress) {
        AtResource atResource = (AtResource) buildResource(AtResource.class, request, response, context);
        return Base58.encode(atResource.getDataByAddress(atAddress));
    }

    @GET
    @Path("/ats")
    @Hidden // For internal Q-App API use only
    public List<ATData> listATs(@QueryParam("codeHash58") String codeHash58, @QueryParam("isExecutable") Boolean isExecutable, @Parameter(ref = "limit") @QueryParam("limit") Integer limit, @Parameter(ref = "offset") @QueryParam("offset") Integer offset, @Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
        AtResource atResource = (AtResource) buildResource(AtResource.class, request, response, context);
        return atResource.getByFunctionality(codeHash58, isExecutable, limit, offset, reverse);
    }


    public static Object buildResource(Class<?> resourceClass, HttpServletRequest request, HttpServletResponse response, ServletContext context) {
        try {
            Object resource = resourceClass.getDeclaredConstructor().newInstance();

            Field requestField = resourceClass.getDeclaredField("request");
            requestField.setAccessible(true);
            requestField.set(resource, request);

            try {
                Field responseField = resourceClass.getDeclaredField("response");
                responseField.setAccessible(true);
                responseField.set(resource, response);
            } catch (NoSuchFieldException e) {
                // Ignore
            }

            try {
                Field contextField = resourceClass.getDeclaredField("context");
                contextField.setAccessible(true);
                contextField.set(resource, context);
            } catch (NoSuchFieldException e) {
                // Ignore
            }

            return resource;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build API resource " + resourceClass.getName() + ": " + e.getMessage(), e);
        }
    }

}
