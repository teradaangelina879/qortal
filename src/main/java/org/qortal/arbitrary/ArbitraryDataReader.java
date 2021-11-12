package org.qortal.arbitrary;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.controller.arbitrary.ArbitraryDataBuildManager;
import org.qortal.controller.arbitrary.ArbitraryDataManager;
import org.qortal.crypto.AES;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.arbitrary.ArbitraryDataFile.*;
import org.qortal.settings.Settings;
import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;
import org.qortal.utils.FilesystemUtils;
import org.qortal.utils.ZipUtils;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class ArbitraryDataReader {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataReader.class);

    private String resourceId;
    private ResourceIdType resourceIdType;
    private Service service;
    private String identifier;
    private ArbitraryTransactionData transactionData;
    private String secret58;
    private Path filePath;

    // Intermediate paths
    private Path workingPath;
    private Path uncompressedPath;
    private Path unencryptedPath;

    public ArbitraryDataReader(String resourceId, ResourceIdType resourceIdType, Service service, String identifier) {
        // Ensure names are always lowercase
        if (resourceIdType == ResourceIdType.NAME) {
            resourceId = resourceId.toLowerCase();
        }

        // If identifier is a blank string, treat it as null
        if (identifier == "") {
            identifier = null;
        }

        this.resourceId = resourceId;
        this.resourceIdType = resourceIdType;
        this.service = service;
        this.identifier = identifier;

        this.workingPath = this.buildWorkingPath();
        this.uncompressedPath = Paths.get(this.workingPath.toString() + File.separator + "data");
    }

    private Path buildWorkingPath() {
        // Use the user-specified temp dir, as it is deterministic, and is more likely to be located on reusable storage hardware
        String baseDir = Settings.getInstance().getTempDataPath();
        String identifier = this.identifier != null ?  this.identifier : "default";
        return Paths.get(baseDir, "reader", this.resourceIdType.toString(), this.resourceId, this.service.toString(), identifier);
    }

    public boolean isCachedDataAvailable() {
        // If this resource is in the build queue then we shouldn't attempt to serve
        // cached data, as it may not be fully built
        ArbitraryDataBuildQueueItem queueItem =
                new ArbitraryDataBuildQueueItem(this.resourceId, this.resourceIdType, this.service, this.identifier);
        if (ArbitraryDataBuildManager.getInstance().isInBuildQueue(queueItem)) {
            return false;
        }

        // Not in the build queue - so check the cache itself
        ArbitraryDataCache cache = new ArbitraryDataCache(this.uncompressedPath, false,
                this.resourceId, this.resourceIdType, this.service, this.identifier);
        if (cache.isCachedDataAvailable()) {
            this.filePath = this.uncompressedPath;
            return true;
        }
        return false;
    }

    /**
     * loadAsynchronously
     *
     * Attempts to load the resource asynchronously
     * This adds the build task to a queue, and the result will be cached when complete
     * To check the status of the build, periodically call isCachedDataAvailable()
     * Once it returns true, you can then use getFilePath() to access the data itself.
     * TODO: create API to check the status
     * @return
     */
    public boolean loadAsynchronously() {
        ArbitraryDataBuildQueueItem queueItem =
                new ArbitraryDataBuildQueueItem(this.resourceId, this.resourceIdType, this.service, this.identifier);
        return ArbitraryDataBuildManager.getInstance().addToBuildQueue(queueItem);
    }

    /**
     * loadSynchronously
     *
     * Attempts to load the resource synchronously
     * Warning: this can block for a long time when building or fetching complex data
     * If no exception is thrown, you can then use getFilePath() to access the data immediately after returning
     *
     * @param overwrite - set to true to force rebuild an existing cache
     * @throws IllegalStateException
     * @throws IOException
     * @throws DataException
     */
    public void loadSynchronously(boolean overwrite) throws IllegalStateException, IOException, DataException, MissingDataException {
        try {
            ArbitraryDataCache cache = new ArbitraryDataCache(this.uncompressedPath, overwrite,
                    this.resourceId, this.resourceIdType, this.service, this.identifier);
            if (cache.isCachedDataAvailable()) {
                // Use cached data
                this.filePath = this.uncompressedPath;
                return;
            }

            this.preExecute();
            this.deleteExistingFiles();
            this.fetch();
            this.decrypt();
            this.uncompress();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() {
        ArbitraryDataBuildManager.getInstance().setBuildInProgress(true);
        this.createWorkingDirectory();
        this.createUncompressedDirectory();
    }

    private void postExecute() {
        ArbitraryDataBuildManager.getInstance().setBuildInProgress(false);
    }

    private void createWorkingDirectory() {
        try {
            Files.createDirectories(this.workingPath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory");
        }
    }

    private void createUncompressedDirectory() {
        try {
            Files.createDirectories(this.uncompressedPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory");
        }
    }

    private void deleteExistingFiles() {
        final Path uncompressedPath = this.uncompressedPath;
        if (FilesystemUtils.pathInsideDataOrTempPath(uncompressedPath)) {
            if (Files.exists(uncompressedPath)) {
                LOGGER.trace("Attempting to delete path {}", this.uncompressedPath);
                try {
                    Files.walkFileTree(uncompressedPath, new SimpleFileVisitor<Path>() {

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                            // Don't delete the parent directory, as we want to leave an empty folder
                            if (dir.compareTo(uncompressedPath) == 0) {
                                return FileVisitResult.CONTINUE;
                            }

                            if (e == null) {
                                Files.delete(dir);
                                return FileVisitResult.CONTINUE;
                            } else {
                                throw e;
                            }
                        }

                    });
                } catch (IOException e) {
                    LOGGER.info("Unable to delete file or directory: {}", e.getMessage());
                }
            }
        }
    }

    private void fetch() throws IllegalStateException, IOException, DataException, MissingDataException {
        switch (resourceIdType) {

            case FILE_HASH:
                this.fetchFromFileHash();
                break;

            case NAME:
                this.fetchFromName();
                break;

            case SIGNATURE:
                this.fetchFromSignature();
                break;

            case TRANSACTION_DATA:
                this.fetchFromTransactionData(this.transactionData);
                break;

            default:
                throw new IllegalStateException(String.format("Unknown resource ID type specified: %s", resourceIdType.toString()));
        }
    }

    private void fetchFromFileHash() {
        // Load data file directly from the hash
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash58(resourceId);
        // Set filePath to the location of the ArbitraryDataFile
        this.filePath = arbitraryDataFile.getFilePath();
    }

    private void fetchFromName() throws IllegalStateException, IOException, DataException, MissingDataException {
        try {

            // Build the existing state using past transactions
            ArbitraryDataBuilder builder = new ArbitraryDataBuilder(this.resourceId, this.service, this.identifier);
            builder.build();
            Path builtPath = builder.getFinalPath();
            if (builtPath == null) {
                throw new IllegalStateException("Unable to build path");
            }

            // Set filePath to the builtPath
            this.filePath = builtPath;

        } catch (InvalidObjectException e) {
            // Hash validation failed. Invalidate the cache for this name, so it can be rebuilt
            LOGGER.info("Deleting {}", this.workingPath.toString());
            FilesystemUtils.safeDeleteDirectory(this.workingPath, false);
            throw(e);
        }
    }

    private void fetchFromSignature() throws IllegalStateException, IOException, DataException, MissingDataException {

        // Load the full transaction data from the database so we can access the file hashes
        ArbitraryTransactionData transactionData;
        try (final Repository repository = RepositoryManager.getRepository()) {
            transactionData = (ArbitraryTransactionData) repository.getTransactionRepository().fromSignature(Base58.decode(resourceId));
        }
        if (!(transactionData instanceof ArbitraryTransactionData)) {
            throw new IllegalStateException(String.format("Transaction data not found for signature %s", this.resourceId));
        }

        this.fetchFromTransactionData(transactionData);
    }

    private void fetchFromTransactionData(ArbitraryTransactionData transactionData) throws IllegalStateException, IOException, MissingDataException {
        if (!(transactionData instanceof ArbitraryTransactionData)) {
            throw new IllegalStateException(String.format("Transaction data not found for signature %s", this.resourceId));
        }

        // Load hashes
        byte[] digest = transactionData.getData();
        byte[] chunkHashes = transactionData.getChunkHashes();

        // Load secret
        byte[] secret = transactionData.getSecret();
        if (secret != null) {
            this.secret58 = Base58.encode(secret);
        }

        // Load data file(s)
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
        if (!arbitraryDataFile.exists()) {
            if (!arbitraryDataFile.allChunksExist(chunkHashes) || chunkHashes == null) {

                // Ask the arbitrary data manager to fetch data for this transaction
                ArbitraryDataManager.getInstance().fetchDataForSignature(transactionData.getSignature());

                // Throw a missing data exception, which allows subsequent layers to fetch data
                String message = String.format("Requested missing data for file %s", arbitraryDataFile);
                LOGGER.info(message);
                throw new MissingDataException(message);
            }
            // We have all the chunks but not the complete file, so join them
            arbitraryDataFile.addChunkHashes(chunkHashes);
            arbitraryDataFile.join();
        }

        // If the complete file still doesn't exist then something went wrong
        if (!arbitraryDataFile.exists()) {
            throw new IOException(String.format("File doesn't exist: %s", arbitraryDataFile));
        }
        // Ensure the complete hash matches the joined chunks
        if (!Arrays.equals(arbitraryDataFile.digest(), digest)) {
            throw new IllegalStateException("Unable to validate complete file hash");
        }
        // Set filePath to the location of the ArbitraryDataFile
        this.filePath = arbitraryDataFile.getFilePath();
    }

    private void decrypt() {
        // Decrypt if we have the secret key.
        byte[] secret = this.secret58 != null ? Base58.decode(this.secret58) : null;
        if (secret != null && secret.length == Transformer.AES256_LENGTH) {
            try {
                this.unencryptedPath = Paths.get(this.workingPath.toString() + File.separator + "zipped.zip");
                SecretKey aesKey = new SecretKeySpec(secret, 0, secret.length, "AES");
                AES.decryptFile("AES", aesKey, this.filePath.toString(), this.unencryptedPath.toString());

                // Replace filePath pointer with the encrypted file path
                // Don't delete the original ArbitraryDataFile, as this is handled in the cleanup phase
                this.filePath = this.unencryptedPath;

            } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchPaddingException
                    | BadPaddingException | IllegalBlockSizeException | IOException | InvalidKeyException e) {
                throw new IllegalStateException(String.format("Unable to decrypt file at path %s: %s", this.filePath, e.getMessage()));
            }
        } else {
            // Assume it is unencrypted. This will be the case when we have built a custom path by combining
            // multiple decrypted archives into a single state.
        }
    }

    private void uncompress() throws IOException {
        if (this.filePath == null || !Files.exists(this.filePath)) {
            throw new IllegalStateException("Can't uncompress non-existent file path");
        }
        File file = new File(this.filePath.toString());
        if (file.isDirectory()) {
            // Already a directory - nothing to uncompress
            // We still need to copy the directory to its final destination if it's not already there
            this.moveFilePathToFinalDestination();
            return;
        }

        try {
            // Handle each type of compression
            if (transactionData.getCompression() == Compression.ZIP) {
                ZipUtils.unzip(this.filePath.toString(), this.uncompressedPath.getParent().toString());
            }
            else if (transactionData.getCompression() == Compression.NONE) {
                Files.createDirectories(this.uncompressedPath);
                Path finalPath = Paths.get(this.uncompressedPath.toString(), "data");
                this.filePath.toFile().renameTo(finalPath.toFile());
            }
            else {
                throw new IllegalStateException(String.format("Unrecognized compression type: %s", transactionData.getCompression()));
            }
        } catch (IOException e) {
            throw new IllegalStateException(String.format("Unable to unzip file: %s", e.getMessage()));
        }

        // If unzipped data was a file not a directory, move it into a data/ directory so that the .qortal
        // metadata folder is able to be created there too
        if (this.uncompressedPath.toFile().isFile()) {
            // Rename to temporary filename
            Path tempDest = Paths.get(this.uncompressedPath.getParent().toString(), "data2");
            this.uncompressedPath.toFile().renameTo(tempDest.toFile());
            // Create a "data" directory
            Files.createDirectories(this.uncompressedPath);
            // Move the original file into the newly created directory
            Path finalPath = Paths.get(this.uncompressedPath.toString(), "data");
            tempDest.toFile().renameTo(finalPath.toFile());
        }

        // Replace filePath pointer with the uncompressed file path
        if (FilesystemUtils.pathInsideDataOrTempPath(this.filePath)) {
            if (Files.exists(this.filePath)) {
                Files.delete(this.filePath);
            }
        }
        this.filePath = this.uncompressedPath;
    }

    private void moveFilePathToFinalDestination() throws IOException {
        if (this.filePath.compareTo(this.uncompressedPath) != 0) {
            File source = new File(this.filePath.toString());
            File dest = new File(this.uncompressedPath.toString());
            if (source == null || !source.exists()) {
                throw new IllegalStateException("Source directory doesn't exist");
            }
            if (dest == null || !dest.exists()) {
                throw new IllegalStateException("Destination directory doesn't exist");
            }
            // Ensure destination directory doesn't exist
            FileUtils.deleteDirectory(dest);
            // Move files to destination
            FilesystemUtils.copyAndReplaceDirectory(source.toString(), dest.toString());

            try {
                // Delete existing
                if (FilesystemUtils.pathInsideDataOrTempPath(this.filePath)) {
                    File directory = new File(this.filePath.toString());
                    FileUtils.deleteDirectory(directory);
                }

                // ... and its parent directory if empty
                Path parentDirectory = this.filePath.getParent();
                if (FilesystemUtils.pathInsideDataOrTempPath(parentDirectory)) {
                    Files.deleteIfExists(parentDirectory);
                }

            } catch (DirectoryNotEmptyException e) {
                    // No need to log anything
            } catch (IOException e) {
                // This will eventually be cleaned up by a maintenance process, so log the error and continue
                LOGGER.info("Unable to cleanup directories: {}", e.getMessage());
            }

            // Finally, update filePath to point to uncompressedPath
            this.filePath = this.uncompressedPath;
        }
    }


    public void setTransactionData(ArbitraryTransactionData transactionData) {
        this.transactionData = transactionData;
    }

    public void setSecret58(String secret58) {
        this.secret58 = secret58;
    }

    public Path getFilePath() {
        return this.filePath;
    }

}
