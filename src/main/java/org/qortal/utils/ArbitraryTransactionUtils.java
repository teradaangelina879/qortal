package org.qortal.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

public class ArbitraryTransactionUtils {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryTransactionUtils.class);

    public static ArbitraryTransactionData fetchTransactionData(final Repository repository, final byte[] signature) {
        try {
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof ArbitraryTransactionData))
                return null;

            return (ArbitraryTransactionData) transactionData;

        } catch (DataException e) {
            LOGGER.error("Repository issue when fetching arbitrary transaction data", e);
            return null;
        }
    }

    public static ArbitraryTransactionData fetchLatestPut(Repository repository, ArbitraryTransactionData arbitraryTransactionData) {
        if (arbitraryTransactionData == null) {
            return null;
        }

        String name = arbitraryTransactionData.getName();
        Service service = arbitraryTransactionData.getService();
        String identifier = arbitraryTransactionData.getIdentifier();

        if (name == null || service == null) {
            return null;
        }

        // Get the most recent PUT for this name and service
        ArbitraryTransactionData latestPut;
        try {
            latestPut = repository.getArbitraryRepository()
                    .getLatestTransaction(name, service, ArbitraryTransactionData.Method.PUT, identifier);
        } catch (DataException e) {
            return null;
        }

        return latestPut;
    }

    public static boolean hasMoreRecentPutTransaction(Repository repository, ArbitraryTransactionData arbitraryTransactionData) {
        byte[] signature = arbitraryTransactionData.getSignature();
        if (signature == null) {
            // We can't make a sensible decision without a signature
            // so it's best to assume there is nothing newer
            return false;
        }

        ArbitraryTransactionData latestPut = ArbitraryTransactionUtils.fetchLatestPut(repository, arbitraryTransactionData);
        if (latestPut == null) {
            return false;
        }

        // If the latest PUT transaction has a newer timestamp, it will override the existing transaction
        // Any data relating to the older transaction is no longer needed
        boolean hasNewerPut = (latestPut.getTimestamp() > arbitraryTransactionData.getTimestamp());
        return hasNewerPut;
    }

    public static boolean completeFileExists(ArbitraryTransactionData transactionData) throws DataException {
        if (transactionData == null) {
            return false;
        }

        byte[] digest = transactionData.getData();

        // Load complete file
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
        return arbitraryDataFile.exists();

    }

    public static boolean allChunksExist(ArbitraryTransactionData transactionData) throws DataException {
        if (transactionData == null) {
            return false;
        }

        byte[] digest = transactionData.getData();
        byte[] chunkHashes = transactionData.getChunkHashes();

        if (chunkHashes == null) {
            // This file doesn't have any chunks, which is the same as us having them all
            return true;
        }

        // Load complete file and chunks
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
        if (chunkHashes != null && chunkHashes.length > 0) {
            arbitraryDataFile.addChunkHashes(chunkHashes);
        }
        return arbitraryDataFile.allChunksExist(chunkHashes);
    }

    public static boolean anyChunksExist(ArbitraryTransactionData transactionData) throws DataException {
        if (transactionData == null) {
            return false;
        }

        byte[] digest = transactionData.getData();
        byte[] chunkHashes = transactionData.getChunkHashes();

        if (chunkHashes == null) {
            // This file doesn't have any chunks, which means none exist
            return false;
        }

        // Load complete file and chunks
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
        if (chunkHashes != null && chunkHashes.length > 0) {
            arbitraryDataFile.addChunkHashes(chunkHashes);
        }
        return arbitraryDataFile.anyChunksExist(chunkHashes);
    }

    public static int ourChunkCount(ArbitraryTransactionData transactionData) throws DataException {
        if (transactionData == null) {
            return 0;
        }

        byte[] digest = transactionData.getData();
        byte[] chunkHashes = transactionData.getChunkHashes();

        if (chunkHashes == null) {
            // This file doesn't have any chunks
            return 0;
        }

        // Load complete file and chunks
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
        if (chunkHashes != null && chunkHashes.length > 0) {
            arbitraryDataFile.addChunkHashes(chunkHashes);
        }
        return arbitraryDataFile.chunkCount();
    }

    public static boolean isFileRecent(Path filePath, long now, long cleanupAfter) {
        try {
            BasicFileAttributes attr = Files.readAttributes(filePath, BasicFileAttributes.class);
            long timeSinceCreated = now - attr.creationTime().toMillis();
            long timeSinceModified = now - attr.lastModifiedTime().toMillis();
            //LOGGER.info(String.format("timeSinceCreated for path %s is %d. cleanupAfter: %d", filePath, timeSinceCreated, cleanupAfter));

            // Check if the file has been created or modified recently
            if (timeSinceCreated > cleanupAfter) {
                return false;
            }
            if (timeSinceModified > cleanupAfter) {
                return false;
            }

        } catch (IOException e) {
            // Can't read file attributes, so assume it's recent so that we don't delete something accidentally
        }
        return true;
    }

    public static boolean isFileHashRecent(byte[] hash, long now, long cleanupAfter) throws DataException {
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash);
        if (arbitraryDataFile == null || !arbitraryDataFile.exists()) {
            // No hash, or file doesn't exist, so it's not recent
            return false;
        }

        Path filePath = arbitraryDataFile.getFilePath();
        return ArbitraryTransactionUtils.isFileRecent(filePath, now, cleanupAfter);
    }

    public static void deleteCompleteFile(ArbitraryTransactionData arbitraryTransactionData, long now, long cleanupAfter) throws DataException {
        byte[] completeHash = arbitraryTransactionData.getData();
        byte[] chunkHashes = arbitraryTransactionData.getChunkHashes();

        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(completeHash);
        arbitraryDataFile.addChunkHashes(chunkHashes);

        if (!ArbitraryTransactionUtils.isFileHashRecent(completeHash, now, cleanupAfter)) {
            LOGGER.info("Deleting file {} because it can be rebuilt from chunks " +
                    "if needed", Base58.encode(completeHash));

            arbitraryDataFile.delete();
        }
    }

    public static void deleteCompleteFileAndChunks(ArbitraryTransactionData arbitraryTransactionData) throws DataException {
        byte[] completeHash = arbitraryTransactionData.getData();
        byte[] chunkHashes = arbitraryTransactionData.getChunkHashes();

        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(completeHash);
        arbitraryDataFile.addChunkHashes(chunkHashes);
        arbitraryDataFile.deleteAll();
    }

    public static void convertFileToChunks(ArbitraryTransactionData arbitraryTransactionData, long now, long cleanupAfter) throws DataException {
        byte[] completeHash = arbitraryTransactionData.getData();
        byte[] chunkHashes = arbitraryTransactionData.getChunkHashes();

        // Split the file into chunks
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(completeHash);
        int chunkCount = arbitraryDataFile.split(ArbitraryDataFile.CHUNK_SIZE);
        if (chunkCount > 1) {
            LOGGER.info(String.format("Successfully split %s into %d chunk%s",
                    Base58.encode(completeHash), chunkCount, (chunkCount == 1 ? "" : "s")));

            // Verify that the chunk hashes match those in the transaction
            if (chunkHashes != null && Arrays.equals(chunkHashes, arbitraryDataFile.chunkHashes())) {
                // Ensure they exist on disk
                if (arbitraryDataFile.allChunksExist(chunkHashes)) {

                    // Now delete the original file if it's not recent
                    if (!ArbitraryTransactionUtils.isFileHashRecent(completeHash, now, cleanupAfter)) {
                        LOGGER.info("Deleting file {} because it can now be rebuilt from " +
                                "chunks if needed", Base58.encode(completeHash));

                        ArbitraryTransactionUtils.deleteCompleteFile(arbitraryTransactionData, now, cleanupAfter);
                    }
                    else {
                        // File might be in use. It's best to leave it and it it will be cleaned up later.
                    }
                }
            }
        }
    }

}
