package org.qortal.api.resource;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.ApiError;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.HTMLParser;
import org.qortal.api.Security;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.storage.DataFile;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.ArbitraryTransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.ZipUtils;


@Path("/site")
@Tag(name = "Website")
public class WebsiteResource {

    private static final Logger LOGGER = LogManager.getLogger(WebsiteResource.class);

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;

    @POST
    @Path("/upload/creator/{publickey}")
    @Operation(
            summary = "Build raw, unsigned, ARBITRARY transaction, based on a user-supplied path to a static website",
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
    public String uploadWebsite(@PathParam("publickey") String creatorPublicKeyBase58, String path) {
        Security.checkApiCallAllowed(request);

        // It's too dangerous to allow user-supplied filenames in weaker security contexts
        if (Settings.getInstance().isApiRestricted()) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);
        }

        if (creatorPublicKeyBase58 == null || path == null) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
        }
        byte[] creatorPublicKey = Base58.decode(creatorPublicKeyBase58);

        DataFile dataFile = this.hostWebsite(path);
        if (dataFile == null) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
        }

        String digest58 = dataFile.digest58();
        if (digest58 == null) {
            LOGGER.error("Unable to calculate digest");
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
        }
        
        try (final Repository repository = RepositoryManager.getRepository()) {

            String creatorAddress = Crypto.toAddress(creatorPublicKey);
            byte[] lastReference = repository.getAccountRepository().getLastReference(creatorAddress);

            BaseTransactionData baseTransactionData = new BaseTransactionData(NTP.getTime(), Group.NO_GROUP,
                    lastReference, creatorPublicKey, BlockChain.getInstance().getUnitFee(), null);
            int size = (int)dataFile.size();
            ArbitraryTransactionData.DataType dataType = ArbitraryTransactionData.DataType.DATA_HASH;
            byte[] digest = dataFile.digest();
            byte[] chunkHashes = dataFile.chunkHashes();
            List<PaymentData> payments = new ArrayList<>();

            ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
                    5, 2, 0, size, digest, dataType, chunkHashes, payments);

            ArbitraryTransaction transaction = (ArbitraryTransaction) Transaction.fromData(repository, transactionData);
            transaction.computeNonce();

            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            if (result != Transaction.ValidationResult.OK) {
                dataFile.deleteAll();
                throw TransactionsResource.createTransactionInvalidException(request, result);
            }

            byte[] bytes = ArbitraryTransactionTransformer.toBytes(transactionData);
            return Base58.encode(bytes);

        } catch (TransformationException e) {
            dataFile.deleteAll();
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
        } catch (DataException e) {
            dataFile.deleteAll();
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
        }
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

        DataFile dataFile = this.hostWebsite(directoryPath);
        if (dataFile != null) {
            String digest58 = dataFile.digest58();
            if (digest58 != null) {
                return "http://localhost:12393/site/" + digest58;
            }
        }
        return "Unable to generate preview URL";
    }

    private DataFile hostWebsite(String directoryPath) {

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
            DataFile dataFile = DataFile.fromPath(outputFilePath);
            DataFile.ValidationResult validationResult = dataFile.isValid();
            if (validationResult != DataFile.ValidationResult.OK) {
                LOGGER.error("Invalid file: {}", validationResult);
                throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
            }
            LOGGER.info("Whole file digest: {}", dataFile.digest58());

            int chunkCount = dataFile.split(DataFile.CHUNK_SIZE);
            if (chunkCount > 0) {
                LOGGER.info(String.format("Successfully split into %d chunk%s:", chunkCount, (chunkCount == 1 ? "" : "s")));
                LOGGER.info("{}", dataFile.printChunks());
                return dataFile;
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
        return this.get(resourceId, "/", true);
    }

    @GET
    @Path("{resource}/{path:.*}")
    public HttpServletResponse getResourcePath(@PathParam("resource") String resourceId, @PathParam("path") String inPath) {
        return this.get(resourceId, inPath, true);
    }

    @GET
    @Path("/domainmap")
    public HttpServletResponse getDomainMapIndex() {
        Map<String, String> domainMap = Settings.getInstance().getSimpleDomainMap();
        if (domainMap != null && domainMap.containsKey(request.getServerName())) {
            return this.get(domainMap.get(request.getServerName()), "/", false);
        }
        return this.get404Response();
    }

    @GET
    @Path("/domainmap/{path:.*}")
    public HttpServletResponse getDomainMapPath(@PathParam("path") String inPath) {
        Map<String, String> domainMap = Settings.getInstance().getSimpleDomainMap();
        if (domainMap != null && domainMap.containsKey(request.getServerName())) {
            return this.get(domainMap.get(request.getServerName()), inPath, false);
        }
        return this.get404Response();
    }

    private HttpServletResponse get(String resourceId, String inPath, boolean usePrefix) {
        if (!inPath.startsWith(File.separator)) {
            inPath = File.separator + inPath;
        }

        String tempDirectory = System.getProperty("java.io.tmpdir");
        String destPath = tempDirectory + File.separator  + "qortal-sites" + File.separator + resourceId;
        String unzippedPath = destPath + File.separator + "data";

        if (!Files.exists(Paths.get(unzippedPath))) {

            // Load the full transaction data so we can access the file hashes
            try (final Repository repository = RepositoryManager.getRepository()) {

                ArbitraryTransactionData transactionData = (ArbitraryTransactionData) repository.getTransactionRepository().fromSignature(Base58.decode(resourceId));
                if (!(transactionData instanceof ArbitraryTransactionData)) {
                    return this.get404Response();
                }

                // Load hashes
                byte[] digest = transactionData.getData();
                byte[] chunkHashes = transactionData.getChunkHashes();

                // Load data file(s)
                DataFile dataFile = DataFile.fromHash(digest);
                if (!dataFile.exists()) {
                    if (!dataFile.allChunksExist(chunkHashes)) {
                        // TODO: fetch them?
                        return this.get404Response();
                    }
                    // We have all the chunks but not the complete file, so join them
                    dataFile.addChunkHashes(chunkHashes);
                    dataFile.join();
                }

                // If the complete file still doesn't exist then something went wrong
                if (!dataFile.exists()) {
                    return this.get404Response();
                }

                if (!Arrays.equals(dataFile.digest(), digest)) {
                    LOGGER.info("Unable to validate complete file hash");
                    return this.get404Response();
                }

                try {
                    ZipUtils.unzip(dataFile.getFilePath(), destPath);
                } catch (IOException e) {
                    LOGGER.info("Unable to unzip file");
                }

            } catch (DataException e) {
                return this.get500Response();
            }
        }

        try {
            String filename = this.getFilename(unzippedPath, inPath);
            String filePath = unzippedPath + File.separator + filename;

            if (HTMLParser.isHtmlFile(filename)) {
                // HTML file - needs to be parsed
                byte[] data = Files.readAllBytes(Paths.get(filePath)); // TODO: limit file size that can be read into memory
                HTMLParser htmlParser = new HTMLParser(resourceId, usePrefix);
                data = htmlParser.replaceRelativeLinks(filename, data);
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

    private HttpServletResponse get500Response() {
        try {
            String responseString = "500: Internal Server Error";
            byte[] responseData = responseString.getBytes();
            response.setStatus(500);
            response.setContentLength(responseData.length);
            response.getOutputStream().write(responseData);
        } catch (IOException e) {
            LOGGER.info("Error writing 500 response");
        }
        return response;
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

}
