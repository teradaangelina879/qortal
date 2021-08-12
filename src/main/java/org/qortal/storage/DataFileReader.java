package org.qortal.storage;

import org.qortal.crypto.AES;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.storage.DataFile.*;
import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;
import org.qortal.utils.ZipUtils;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class DataFileReader {

    private String resourceId;
    private ResourceIdType resourceIdType;
    private String secret58;
    private Path filePath;
    private DataFile dataFile;

    // Intermediate paths
    private Path workingPath;
    private Path uncompressedPath;
    private Path unencryptedPath;

    public DataFileReader(String resourceId, ResourceIdType resourceIdType) {
        this.resourceId = resourceId;
        this.resourceIdType = resourceIdType;
    }

    public void load(boolean overwrite) throws IllegalStateException, IOException, DataException {

        try {
            this.preExecute();

            // Do nothing if files already exist and overwrite is set to false
            if (Files.exists(this.uncompressedPath) && !overwrite) {
                this.filePath = this.uncompressedPath;
                return;
            }

            this.fetch();
            this.decrypt();
            this.uncompress();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() {
        this.createWorkingDirectory();
        // Initialize unzipped path as it's used in a few places
        this.uncompressedPath = Paths.get(this.workingPath.toString() + File.separator + "data");
    }

    private void postExecute() throws IOException {
        this.cleanupFilesystem();
    }

    private void createWorkingDirectory() {
        // Use the system tmpdir as our base, as it is deterministic
        String baseDir = System.getProperty("java.io.tmpdir");
        Path tempDir = Paths.get(baseDir + File.separator  + "qortal" + File.separator + this.resourceId);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory");
        }
        this.workingPath = tempDir;
    }

    private void fetch() throws IllegalStateException, IOException, DataException {
        switch (resourceIdType) {

            case SIGNATURE:
                this.fetchFromSignature();
                break;

            case FILE_HASH:
                this.fetchFromFileHash();
                break;

            default:
                throw new IllegalStateException(String.format("Unknown resource ID type specified: %s", resourceIdType.toString()));
        }
    }

    private void fetchFromSignature() throws IllegalStateException, IOException, DataException {

        // Load the full transaction data so we can access the file hashes
        ArbitraryTransactionData transactionData;
        try (final Repository repository = RepositoryManager.getRepository()) {
            transactionData = (ArbitraryTransactionData) repository.getTransactionRepository().fromSignature(Base58.decode(resourceId));
        }
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
        this.dataFile = DataFile.fromHash(digest);
        if (!this.dataFile.exists()) {
            if (!this.dataFile.allChunksExist(chunkHashes)) {
                // TODO: fetch them?
                throw new IllegalStateException(String.format("Missing chunks for file {}", dataFile));
            }
            // We have all the chunks but not the complete file, so join them
            this.dataFile.addChunkHashes(chunkHashes);
            this.dataFile.join();
        }

        // If the complete file still doesn't exist then something went wrong
        if (!this.dataFile.exists()) {
            throw new IOException(String.format("File doesn't exist: %s", dataFile));
        }
        // Ensure the complete hash matches the joined chunks
        if (!Arrays.equals(dataFile.digest(), digest)) {
            throw new IllegalStateException("Unable to validate complete file hash");
        }
        // Set filePath to the location of the DataFile
        this.filePath = Paths.get(dataFile.getFilePath());
    }

    private void fetchFromFileHash() {
        // Load data file directly from the hash
        this.dataFile = DataFile.fromHash58(resourceId);
        // Set filePath to the location of the DataFile
        this.filePath = Paths.get(dataFile.getFilePath());
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
                // Don't delete the original DataFile, as this is handled in the cleanup phase
                this.filePath = this.unencryptedPath;

            } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchPaddingException
                    | BadPaddingException | IllegalBlockSizeException | IOException | InvalidKeyException e) {
                throw new IllegalStateException(String.format("Unable to decrypt file %s: %s", dataFile, e.getMessage()));
            }
        } else {
            // Assume it is unencrypted. We may block this in the future.
            this.filePath = Paths.get(this.dataFile.getFilePath());
        }
    }

    private void uncompress() throws IOException {
        try {
            // TODO: compression types
            //if (transactionData.getCompression() == ArbitraryTransactionData.Compression.ZIP) {
                ZipUtils.unzip(this.filePath.toString(), this.uncompressedPath.getParent().toString());
            //}
        } catch (IOException e) {
            throw new IllegalStateException(String.format("Unable to unzip file: %s", e.getMessage()));
        }

        // Replace filePath pointer with the uncompressed file path
        Files.delete(this.filePath);
        this.filePath = this.uncompressedPath;
    }

    private void cleanupFilesystem() throws IOException {
        // Clean up
        if (this.uncompressedPath != null) {
            File unzippedFile = new File(this.uncompressedPath.toString());
            if (unzippedFile.exists()) {
                unzippedFile.delete();
            }
        }
    }


    public void setSecret58(String secret58) {
        this.secret58 = secret58;
    }

    public Path getFilePath() {
        return this.filePath;
    }

}
