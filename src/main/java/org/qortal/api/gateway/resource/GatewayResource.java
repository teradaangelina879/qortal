package org.qortal.api.gateway.resource;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.Security;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.ArbitraryDataRenderer;
import org.qortal.arbitrary.misc.Service;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;


@Path("/")
@Tag(name = "Gateway")
public class GatewayResource {

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;

    @GET
    @Path("{name}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathByName(@PathParam("name") String name,
                                             @PathParam("path") String inPath) {
        // Block requests from localhost, to prevent websites/apps from running javascript that fetches unvetted data
        Security.disallowLoopbackRequests(request);
        return this.get(name, ResourceIdType.NAME, Service.WEBSITE, inPath, null, "", true, true);
    }

    @GET
    @Path("{name}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexByName(@PathParam("name") String name) {
        // Block requests from localhost, to prevent websites/apps from running javascript that fetches unvetted data
        Security.disallowLoopbackRequests(request);
        return this.get(name, ResourceIdType.NAME, Service.WEBSITE, "/", null, "", true, true);
    }


    // Optional /site alternative for backwards support

    @GET
    @Path("/site/{name}/{path:.*}")
    public HttpServletResponse getSitePathByName(@PathParam("name") String name,
                                                 @PathParam("path") String inPath) {
        // Block requests from localhost, to prevent websites/apps from running javascript that fetches unvetted data
        Security.disallowLoopbackRequests(request);
        return this.get(name, ResourceIdType.NAME, Service.WEBSITE, inPath, null, "/site", true, true);
    }

    @GET
    @Path("/site/{name}")
    public HttpServletResponse getSiteIndexByName(@PathParam("name") String name) {
        // Block requests from localhost, to prevent websites/apps from running javascript that fetches unvetted data
        Security.disallowLoopbackRequests(request);
        return this.get(name, ResourceIdType.NAME, Service.WEBSITE, "/", null, "/site", true, true);
    }

    
    private HttpServletResponse get(String resourceId, ResourceIdType resourceIdType, Service service, String inPath,
                                    String secret58, String prefix, boolean usePrefix, boolean async) {

        ArbitraryDataRenderer renderer = new ArbitraryDataRenderer(resourceId, resourceIdType, service, inPath,
                secret58, prefix, usePrefix, async, request, response, context);
        return renderer.render();
    }

}
