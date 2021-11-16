package org.qortal.api.resource;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.*;
import java.nio.file.Paths;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.ApiError;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.arbitrary.misc.Service;
import org.qortal.arbitrary.*;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.data.transaction.ArbitraryTransactionData.*;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;
import org.qortal.arbitrary.ArbitraryDataFile.*;
import org.qortal.utils.Base58;


@Path("/site")
@Tag(name = "Website")
public class WebsiteResource {

    private static final Logger LOGGER = LogManager.getLogger(WebsiteResource.class);

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;

    @POST
    @Path("/preview")
    @Operation(
            summary = "Generate preview URL based on a user-supplied path to a static website",
            requestBody = @RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.TEXT_PLAIN,
                            schema = @Schema(
                                    type = "string", example = "/Users/user/Documents/MyStaticWebsite"
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            description = "a temporary URL to preview the website",
                            content = @Content(
                                    mediaType = MediaType.TEXT_PLAIN,
                                    schema = @Schema(
                                            type = "string"
                                    )
                            )
                    )
            }
    )
    @SecurityRequirement(name = "apiKey")
    public String previewWebsite(String directoryPath) {
        Security.checkApiCallAllowed(request);

        // It's too dangerous to allow user-supplied filenames in weaker security contexts
        if (Settings.getInstance().isApiRestricted()) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);
        }

        String name = null;
        Service service = Service.WEBSITE;
        Method method = Method.PUT;
        Compression compression = Compression.ZIP;

        ArbitraryDataWriter arbitraryDataWriter = new ArbitraryDataWriter(Paths.get(directoryPath), name, service, null, method, compression);
        try {
            arbitraryDataWriter.save();
        } catch (IOException | DataException | InterruptedException | MissingDataException e) {
            LOGGER.info("Unable to create arbitrary data file: {}", e.getMessage());
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE);
        } catch (RuntimeException e) {
            LOGGER.info("Unable to create arbitrary data file: {}", e.getMessage());
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
        }

        ArbitraryDataFile arbitraryDataFile = arbitraryDataWriter.getArbitraryDataFile();
        if (arbitraryDataFile != null) {
            String digest58 = arbitraryDataFile.digest58();
            if (digest58 != null) {
                return "http://localhost:12393/site/hash/" + digest58 + "?secret=" + Base58.encode(arbitraryDataFile.getSecret());
            }
        }
        return "Unable to generate preview URL";
    }

    @GET
    @Path("/signature/{signature}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexBySignature(@PathParam("signature") String signature) {
        Security.checkApiCallAllowed(request);
        return this.get(signature, ResourceIdType.SIGNATURE, "/", null, "/site/signature", true, true);
    }

    @GET
    @Path("/signature/{signature}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathBySignature(@PathParam("signature") String signature, @PathParam("path") String inPath) {
        Security.checkApiCallAllowed(request);
        return this.get(signature, ResourceIdType.SIGNATURE, inPath,null, "/site/signature", true, true);
    }

    @GET
    @Path("/hash/{hash}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexByHash(@PathParam("hash") String hash58, @QueryParam("secret") String secret58) {
        Security.checkApiCallAllowed(request);
        return this.get(hash58, ResourceIdType.FILE_HASH, "/", secret58, "/site/hash", true, false);
    }

    @GET
    @Path("/hash/{hash}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathByHash(@PathParam("hash") String hash58, @PathParam("path") String inPath,
                                             @QueryParam("secret") String secret58) {
        Security.checkApiCallAllowed(request);
        return this.get(hash58, ResourceIdType.FILE_HASH, inPath, secret58, "/site/hash", true, false);
    }

    @GET
    @Path("{name}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathByName(@PathParam("name") String name, @PathParam("path") String inPath) {
        Security.checkApiCallAllowed(request);
        return this.get(name, ResourceIdType.NAME, inPath, null, "/site", true, true);
    }

    @GET
    @Path("{name}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexByName(@PathParam("name") String name) {
        Security.checkApiCallAllowed(request);
        return this.get(name, ResourceIdType.NAME, "/", null, "/site", true, true);
    }

    @GET
    @Path("/domainmap")
    public HttpServletResponse getIndexByDomainMap() {
        return this.getDomainMap("/");
    }

    @GET
    @Path("/domainmap/{path:.*}")
    public HttpServletResponse getPathByDomainMap(@PathParam("path") String inPath) {
        return this.getDomainMap(inPath);
    }

    private HttpServletResponse getDomainMap(String inPath) {
        Map<String, String> domainMap = Settings.getInstance().getSimpleDomainMap();
        if (domainMap != null && domainMap.containsKey(request.getServerName())) {
            return this.get(domainMap.get(request.getServerName()), ResourceIdType.NAME, inPath, null, "", false, true);
        }
        return ArbitraryDataRenderer.getResponse(response, 404, "Error 404: File Not Found");
    }

    private HttpServletResponse get(String resourceId, ResourceIdType resourceIdType, String inPath, String secret58,
                                    String prefix, boolean usePrefix, boolean async) {

        ArbitraryDataRenderer renderer = new ArbitraryDataRenderer(resourceId, resourceIdType, inPath, secret58,
                prefix, usePrefix, async, request, response, context);
        return renderer.render();
    }

}
