package org.qortal.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.crypto.Crypto;
import org.qortal.utils.Base58;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class DataFileChunk extends DataFile {

    private static final Logger LOGGER = LogManager.getLogger(DataFileChunk.class);

    public DataFileChunk() {
    }

    public DataFileChunk(byte[] fileContent) {
        if (fileContent == null) {
            LOGGER.error("Chunk fileContent is null");
            return;
        }

        String base58Digest = Base58.encode(Crypto.digest(fileContent));
        LOGGER.debug(String.format("Chunk digest: %s, size: %d bytes", base58Digest, fileContent.length));

        String outputFilePath = this.getOutputFilePath(base58Digest);
        File outputFile = new File(outputFilePath);
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(fileContent);
            this.filePath = outputFilePath;
            // Verify hash
            if (!base58Digest.equals(this.base58Digest())) {
                LOGGER.error("Digest {} does not match file digest {}", base58Digest, this.base58Digest());
                throw new IllegalStateException("DataFileChunk digest validation failed");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write chunk data to file");
        }
    }

    @Override
    public ValidationResult isValid() {
        // DataChunk validation applies here too
        ValidationResult superclassValidationResult = super.isValid();
        if (superclassValidationResult != ValidationResult.OK) {
            return superclassValidationResult;
        }

        Path path = Paths.get(this.filePath);
        try {
            // Validate the file size (chunks have stricter limits)
            long fileSize = Files.size(path);
            if (fileSize > CHUNK_SIZE) {
                LOGGER.error(String.format("DataFileChunk is too large: %d bytes (max chunk size: %d bytes)", fileSize, CHUNK_SIZE));
                return ValidationResult.FILE_TOO_LARGE;
            }

        } catch (IOException e) {
            return ValidationResult.FILE_NOT_FOUND;
        }

        return ValidationResult.OK;
    }
}
