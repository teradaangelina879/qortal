package org.qortal.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class DataFileChunk extends DataFile {

    private static final Logger LOGGER = LogManager.getLogger(DataFileChunk.class);

    public DataFileChunk(String hash58) {
        super(hash58);
    }

    public DataFileChunk(byte[] fileContent) {
        super(fileContent);
    }

    public static DataFileChunk fromHash58(String hash58) {
        return new DataFileChunk(hash58);
    }

    public static DataFileChunk fromHash(byte[] hash) {
        return DataFileChunk.fromHash58(Base58.encode(hash));
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
