package org.qortal.arbitrary;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
    private ArbitraryTransactionData transactionData;
    private String secret58;
    private Path filePath;

    // Intermediate paths
    private Path workingPath;
    private Path uncompressedPath;
    private Path unencryptedPath;

    public ArbitraryDataReader(String resourceId, ResourceIdType resourceIdType, Service service) {
        this.resourceId = resourceId;
        this.resourceIdType = resourceIdType;
        this.service = service;
    }

    public void load(boolean overwrite) throws IllegalStateException, IOException, DataException {
        try {
            this.preExecute();

            // Do nothing if files already exist and overwrite is set to false
            if (!overwrite && Files.exists(this.uncompressedPath)
                    && !FilesystemUtils.isDirectoryEmpty(this.uncompressedPath)) {
                this.filePath = this.uncompressedPath;
                return;
            }

            this.deleteExistingFiles();
            this.fetch();
            this.decrypt();
            this.uncompress();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() {
        this.createWorkingDirectory();
        this.createUncompressedDirectory();
    }

    private void postExecute() {

    }

    private void createWorkingDirectory() {
        // Use the user-specified temp dir, as it is deterministic, and is more likely to be located on reusable storage hardware
        String baseDir = Settings.getInstance().getTempDataPath();
        Path tempDir = Paths.get(baseDir, "reader", this.resourceId);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory");
        }
        this.workingPath = tempDir;
    }

    private void createUncompressedDirectory() {
        this.uncompressedPath = Paths.get(this.workingPath.toString() + File.separator + "data");
        try {
            Files.createDirectories(this.uncompressedPath);
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

    private void fetch() throws IllegalStateException, IOException, DataException {
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
        this.filePath = Paths.get(arbitraryDataFile.getFilePath());
    }

    private void fetchFromName() throws IllegalStateException, IOException, DataException {

        // Build the existing state using past transactions
        ArbitraryDataBuilder builder = new ArbitraryDataBuilder(this.resourceId, this.service);
        builder.build();
        Path builtPath = builder.getFinalPath();
        if (builtPath == null) {
            throw new IllegalStateException("Unable to build path");
        }

        // Set filePath to the builtPath
        this.filePath = builtPath;
    }

    private void fetchFromSignature() throws IllegalStateException, IOException, DataException {

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

    private void fetchFromTransactionData(ArbitraryTransactionData transactionData) throws IllegalStateException, IOException, DataException {
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
            if (!arbitraryDataFile.allChunksExist(chunkHashes)) {
                // TODO: fetch them?
                throw new IllegalStateException(String.format("Missing chunks for file {}", arbitraryDataFile));
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
        this.filePath = Paths.get(arbitraryDataFile.getFilePath());
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
            // TODO: compression types
            //if (transactionData.getCompression() == ArbitraryTransactionData.Compression.ZIP) {
                ZipUtils.unzip(this.filePath.toString(), this.uncompressedPath.getParent().toString());
            //}
        } catch (IOException e) {
            throw new IllegalStateException(String.format("Unable to unzip file: %s", e.getMessage()));
        }

        // Replace filePath pointer with the uncompressed file path
        if (FilesystemUtils.pathInsideDataOrTempPath(this.filePath)) {
            Files.delete(this.filePath);
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
