package org.qortal.api.resource;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.qortal.api.ApiError;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.settings.Settings;
import org.qortal.storage.DataFile;
import org.qortal.utils.ZipUtils;


@Path("/site")
@Tag(name = "Website")
public class WebsiteResource {

    private static final Logger LOGGER = LogManager.getLogger(WebsiteResource.class);

    @Context
    HttpServletRequest request;

    @POST
    @Path("/upload")
    @Operation(
            summary = "Build raw, unsigned, UPLOAD_DATA transaction, based on a user-supplied path to a static website",
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
    public String uploadWebsite(String directoryPath) {
        Security.checkApiCallAllowed(request);

        // It's too dangerous to allow user-supplied filenames in weaker security contexts
        if (Settings.getInstance().isApiRestricted()) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);
        }

        String base58Digest = this.hostWebsite(directoryPath);
        if (base58Digest != null) {
            // TODO: build transaction
            return "true";
        }
        return "false";
    }

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
    public String previewWebsite(String directoryPath) {
        Security.checkApiCallAllowed(request);

        // It's too dangerous to allow user-supplied filenames in weaker security contexts
        if (Settings.getInstance().isApiRestricted()) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);
        }

        String base58Digest = this.hostWebsite(directoryPath);
        if (base58Digest != null) {
            return "http://localhost:12393/site/" + base58Digest;
        }
        return "Unable to generate preview URL";
    }

    private String hostWebsite(String directoryPath) {

        // Check if a file or directory has been supplied
        File file = new File(directoryPath);
        if (!file.isDirectory()) {
            LOGGER.info("Not a directory: {}", directoryPath);
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
        }

        // Ensure temp folder exists
        try {
            Files.createDirectories(Paths.get("temp"));
        } catch (IOException e) {
            LOGGER.error("Unable to create temp directory");
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE);
        }

        // Firstly zip up the directory
        String outputFilePath = "temp/zipped.zip";
        try {
            ZipUtils.zip(directoryPath, outputFilePath, "data");
        } catch (IOException e) {
            LOGGER.info("Unable to zip directory", e);
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
        }

        try {
            DataFile dataFile = new DataFile(outputFilePath);
            DataFile.ValidationResult validationResult = dataFile.isValid();
            if (validationResult != DataFile.ValidationResult.OK) {
                LOGGER.error("Invalid file: {}", validationResult);
                throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
            }
            LOGGER.info("Whole file digest: {}", dataFile.base58Digest());

            int chunkCount = dataFile.split(DataFile.CHUNK_SIZE);
            if (chunkCount > 0) {
                LOGGER.info(String.format("Successfully split into %d chunk%s", chunkCount, (chunkCount == 1 ? "" : "s")));
                return dataFile.base58Digest();
            }

            return null;
        }
        finally {
            // Clean up by deleting the zipped file
            File zippedFile = new File(outputFilePath);
            if (zippedFile.exists()) {
                zippedFile.delete();
            }
        }
    }

    @GET
    @Path("{resource}")
    public Response getResourceIndex(@PathParam("resource") String resourceId) {
        return this.get(resourceId, "/");
    }

    @GET
    @Path("{resource}/{path:.*}")
    public Response getResourcePath(@PathParam("resource") String resourceId, @PathParam("path") String inPath) {
        return this.get(resourceId, inPath);
    }

    private Response get(String resourceId, String inPath) {
        if (!inPath.startsWith(File.separator)) {
            inPath = File.separator + inPath;
        }

        String tempDirectory = System.getProperty("java.io.tmpdir");
        String destPath = tempDirectory + "qortal-sites" + File.separator + resourceId;
        String unzippedPath = destPath + File.separator + "data";

        if (!Files.exists(Paths.get(unzippedPath))) {

            // Load file
            DataFile dataFile = DataFile.fromBase58Digest(resourceId);
            if (dataFile == null || !dataFile.exists()) {
                LOGGER.info("Unable to validate complete file hash");
                return Response.serverError().build();
            }

            String newHash = dataFile.base58Digest();
            LOGGER.info("newHash: {}", newHash);
            if (!dataFile.base58Digest().equals(resourceId)) {
                LOGGER.info("Unable to validate complete file hash");
                return Response.serverError().build();
            }

            try {
                ZipUtils.unzip(dataFile.getFilePath(), destPath);
            } catch (IOException e) {
                LOGGER.info("Unable to unzip file");
            }
        }

        try {
            String filename = this.getFilename(unzippedPath, inPath);
            byte[] data = Files.readAllBytes(Paths.get(unzippedPath + File.separator + filename)); // TODO: limit file size that can be read into memory
            data = this.replaceRelativeLinks(filename, data, resourceId);
            return Response.ok(data).build();
        } catch (IOException e) {
            LOGGER.info("Unable to serve file at path: {}", inPath);
        }

        return Response.serverError().build();
    }

    private String getFilename(String directory, String userPath) {
        if (userPath == null || userPath.endsWith("/") || userPath.equals("")) {
            // Locate index file
            List<String> indexFiles = this.indexFiles();
            for (String indexFile : indexFiles) {
                String filePath = directory + File.separator + indexFile;
                if (Files.exists(Paths.get(filePath))) {
                    return userPath + indexFile;
                }
            }
        }
        return userPath;
    }

    /**
     * Find relative links and prefix them with the resource ID, using Jsoup
     * @param path
     * @param data
     * @param resourceId
     * @return The data with links replaced
     */
    private byte[] replaceRelativeLinks(String path, byte[] data, String resourceId) {
        if (this.isHtmlFile(path)) {
            String fileContents = new String(data);
            Document document = Jsoup.parse(fileContents);

            Elements href = document.select("[href]");
            for (Element element : href)  {
                String elementHtml = element.attr("href");
                if (!elementHtml.startsWith("http") && !elementHtml.startsWith("//")) {
                    String slash = (elementHtml.startsWith("/") ? "" : File.separator);
                    element.attr("href", "/site/" +resourceId + slash + element.attr("href"));
                }
            }
            Elements src = document.select("[src]");
            for (Element element : src)  {
                String elementHtml = element.attr("src");
                if (!elementHtml.startsWith("http") && !elementHtml.startsWith("//")) {
                    String slash = (elementHtml.startsWith("/") ? "" : File.separator);
                    element.attr("src", "/site/" +resourceId + slash + element.attr("src"));
                }
            }
            return document.html().getBytes();
        }
        return data;
    }

    private List<String> indexFiles() {
        List<String> indexFiles = new ArrayList<>();
        indexFiles.add("index.html");
        indexFiles.add("index.htm");
        indexFiles.add("default.html");
        indexFiles.add("default.htm");
        indexFiles.add("home.html");
        indexFiles.add("home.htm");
        return indexFiles;
    }

    private boolean isHtmlFile(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) {
            return true;
        }
        return false;
    }

}
