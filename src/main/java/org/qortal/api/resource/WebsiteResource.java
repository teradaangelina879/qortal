package org.qortal.api.resource;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.*;
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

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;

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
        java.nio.file.Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("qortal-zip");
        } catch (IOException e) {
            LOGGER.error("Unable to create temp directory");
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE);
        }

        // Firstly zip up the directory
        String outputFilePath = tempDir.toString() + File.separator + "zipped.zip";
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
                LOGGER.info(String.format("Successfully split into %d chunk%s:", chunkCount, (chunkCount == 1 ? "" : "s")));
                LOGGER.info("{}", dataFile.printChunks());
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
    public HttpServletResponse getResourceIndex(@PathParam("resource") String resourceId) {
        return this.get(resourceId, "/");
    }

    @GET
    @Path("{resource}/{path:.*}")
    public HttpServletResponse getResourcePath(@PathParam("resource") String resourceId, @PathParam("path") String inPath) {
        return this.get(resourceId, inPath);
    }

    private HttpServletResponse get(String resourceId, String inPath) {
        if (!inPath.startsWith(File.separator)) {
            inPath = File.separator + inPath;
        }

        String tempDirectory = System.getProperty("java.io.tmpdir");
        String destPath = tempDirectory + File.separator  + "qortal-sites" + File.separator + resourceId;
        String unzippedPath = destPath + File.separator + "data";

        if (!Files.exists(Paths.get(unzippedPath))) {

            // Load file
            DataFile dataFile = DataFile.fromBase58Digest(resourceId);
            if (dataFile == null || !dataFile.exists()) {
                LOGGER.info("Unable to validate complete file hash");
                return this.get404Response();
            }

            if (!dataFile.base58Digest().equals(resourceId)) {
                LOGGER.info("Unable to validate complete file hash");
                return this.get404Response();
            }

            try {
                ZipUtils.unzip(dataFile.getFilePath(), destPath);
            } catch (IOException e) {
                LOGGER.info("Unable to unzip file");
            }
        }

        try {
            String filename = this.getFilename(unzippedPath, inPath);
            String filePath = unzippedPath + File.separator + filename;

            if (this.isHtmlFile(filename)) {
                // HTML file - needs to be parsed
                byte[] data = Files.readAllBytes(Paths.get(filePath)); // TODO: limit file size that can be read into memory
                data = this.replaceRelativeLinks(filename, data, resourceId);
                response.setContentType(context.getMimeType(filename));
                response.setContentLength(data.length);
                response.getOutputStream().write(data);
            }
            else {
                // Regular file - can be streamed directly
                File file = new File(filePath);
                FileInputStream inputStream = new FileInputStream(file);
                response.setContentType(context.getMimeType(filename));
                int bytesRead, length = 0;
                byte[] buffer = new byte[1024];
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    response.getOutputStream().write(buffer, 0, bytesRead);
                    length += bytesRead;
                }
                response.setContentLength(length);
                inputStream.close();
            }
            return response;
        } catch (IOException e) {
            LOGGER.info("Unable to serve file at path: {}", inPath);
        }

        return this.get404Response();
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

    private HttpServletResponse get404Response() {
        try {
            String responseString = "404: File Not Found";
            byte[] responseData = responseString.getBytes();
            response.setStatus(404);
            response.setContentLength(responseData.length);
            response.getOutputStream().write(responseData);
        } catch (IOException e) {
            LOGGER.info("Error writing 404 response");
        }
        return response;
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
                if (this.shouldReplaceLink(elementHtml)) {
                    String slash = (elementHtml.startsWith("/") ? "" : File.separator);
                    element.attr("href", "/site/" +resourceId + slash + element.attr("href"));
                }
            }
            Elements src = document.select("[src]");
            for (Element element : src)  {
                String elementHtml = element.attr("src");
                if (this.shouldReplaceLink(elementHtml)) {
                    String slash = (elementHtml.startsWith("/") ? "" : File.separator);
                    element.attr("src", "/site/" +resourceId + slash + element.attr("src"));
                }
            }
            Elements srcset = document.select("[srcset]");
            for (Element element : srcset)  {
                String elementHtml = element.attr("srcset").trim();
                if (this.shouldReplaceLink(elementHtml)) {
                    String[] parts = element.attr("srcset").split(",");
                    ArrayList<String> newParts = new ArrayList<>();
                    for (String part : parts) {
                        part = part.trim();
                        String slash = (elementHtml.startsWith("/") ? "" : File.separator);
                        String newPart = "/site/" +resourceId + slash + part;
                        newParts.add(newPart);
                    }
                    String newString = String.join(",", newParts);
                    element.attr("srcset", newString);
                }
            }
            Elements style = document.select("[style]");
            for (Element element : style)  {
                String elementHtml = element.attr("style");
                if (elementHtml.contains("url(")) {
                    String[] parts = elementHtml.split("url\\(");
                    String[] parts2 = parts[1].split("\\)");
                    String link = parts2[0];
                    if (link != null) {
                        link = this.removeQuotes(link);
                        if (this.shouldReplaceLink(link)) {
                            String slash = (link.startsWith("/") ? "" : "/");
                            String modifiedLink = "url('" + "/site/" + resourceId + slash + link + "')";
                            element.attr("style", parts[0] + modifiedLink + parts2[1]);
                        }
                    }
                }
            }
            return document.html().getBytes();
        }
        return data;
    }

    private boolean shouldReplaceLink(String elementHtml) {
        List<String> prefixes = new ArrayList<>();
        prefixes.add("http"); // Don't modify absolute links
        prefixes.add("//"); // Don't modify absolute links
        prefixes.add("javascript:"); // Don't modify javascript
        prefixes.add("../"); // Don't modify valid relative links
        for (String prefix : prefixes) {
            if (elementHtml.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private String removeQuotes(String elementHtml) {
        if (elementHtml.startsWith("\"") || elementHtml.startsWith("\'")) {
            elementHtml = elementHtml.substring(1);
        }
        if (elementHtml.endsWith("\"") || elementHtml.endsWith("\'")) {
            elementHtml = elementHtml.substring(0, elementHtml.length() - 1);
        }
        return elementHtml;
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
