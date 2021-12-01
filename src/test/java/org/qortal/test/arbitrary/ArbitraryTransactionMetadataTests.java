package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.ArbitraryDataDigest;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataFile.*;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.ArbitraryDataTransactionBuilder;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryTransactionMetadataTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testMultipleChunks() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.WEBSITE; // Can be anything for this test
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = generateRandomDataPath(dataLength);
            ArbitraryDataFile arbitraryDataFile = this.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize);

            // Check the chunk count is correct
            assertEquals(10, arbitraryDataFile.chunkCount());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);
            Path initialLayerPath = arbitraryDataReader.getFilePath();
            ArbitraryDataDigest initialLayerDigest = new ArbitraryDataDigest(initialLayerPath);
            initialLayerDigest.compute();

            // Its directory hash should match the original directory hash
            ArbitraryDataDigest path1Digest = new ArbitraryDataDigest(path1);
            path1Digest.compute();
            assertEquals(path1Digest.getHash58(), initialLayerDigest.getHash58());
        }
    }


    private Path generateRandomDataPath(int length) throws IOException {
        // Create a file in a random temp directory
        Path tempDir = Files.createTempDirectory("generateRandomDataPath");
        File file = new File(Paths.get(tempDir.toString(), "file.txt").toString());
        file.deleteOnExit();

        // Write a random string to the file
        BufferedWriter file1Writer = new BufferedWriter(new FileWriter(file));
        String initialString = this.generateRandomString(length - 1); // -1 due to newline at EOF

        // Add a newline every 50 chars
        // initialString = initialString.replaceAll("(.{50})", "$1\n");

        file1Writer.write(initialString);
        file1Writer.newLine();
        file1Writer.close();

        return tempDir;
    }

    private String generateRandomString(int length) {
        int leftLimit = 48; // numeral '0'
        int rightLimit = 122; // letter 'z'
        Random random = new Random();

        return random.ints(leftLimit, rightLimit + 1)
                .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                .limit(length)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private ArbitraryDataFile createAndMintTxn(Repository repository, String publicKey58, Path path, String name, String identifier,
                                               ArbitraryTransactionData.Method method, Service service, PrivateKeyAccount account,
                                               int chunkSize) throws DataException {

        ArbitraryDataTransactionBuilder txnBuilder = new ArbitraryDataTransactionBuilder(
                repository, publicKey58, path, name, method, service, identifier);

        txnBuilder.setChunkSize(chunkSize);
        txnBuilder.build();
        txnBuilder.computeNonce();
        ArbitraryTransactionData transactionData = txnBuilder.getArbitraryTransactionData();
        Transaction.ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, account);
        assertEquals(Transaction.ValidationResult.OK, result);
        BlockUtils.mintBlock(repository);

        // We need a new ArbitraryDataFile instance because the files will have been moved to the signature's folder
        byte[] hash = txnBuilder.getArbitraryDataFile().getHash();
        byte[] signature = transactionData.getSignature();
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, signature);
        arbitraryDataFile.setMetadataHash(transactionData.getMetadataHash());

        return arbitraryDataFile;
    }

}
