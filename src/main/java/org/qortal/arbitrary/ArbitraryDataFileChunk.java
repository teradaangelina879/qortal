package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.repository.DataException;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.nio.file.Files;


public class ArbitraryDataFileChunk extends ArbitraryDataFile {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileChunk.class);

    public ArbitraryDataFileChunk(String hash58) throws DataException {
        super(hash58);
    }

    public ArbitraryDataFileChunk(byte[] fileContent) throws DataException {
        super(fileContent);
    }

    public static ArbitraryDataFileChunk fromHash58(String hash58) throws DataException {
        return new ArbitraryDataFileChunk(hash58);
    }

    public static ArbitraryDataFileChunk fromHash(byte[] hash) throws DataException {
        return ArbitraryDataFileChunk.fromHash58(Base58.encode(hash));
    }

    @Override
    public ValidationResult isValid() {
        // DataChunk validation applies here too
        ValidationResult superclassValidationResult = super.isValid();
        if (superclassValidationResult != ValidationResult.OK) {
            return superclassValidationResult;
        }

        try {
            // Validate the file size (chunks have stricter limits)
            long fileSize = Files.size(this.filePath);
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
