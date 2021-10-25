package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.ArbitraryDataDigest;
import org.qortal.arbitrary.ArbitraryDataFile.*;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.ArbitraryDataTransactionBuilder;
import org.qortal.arbitrary.metadata.ArbitraryDataMetadataPatch;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.*;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class ArbitraryDataTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testCombineMultipleLayers() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            Service service = Service.WEBSITE; // Can be anything for this test

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = Paths.get("src/test/resources/arbitrary/demo1");
            this.createAndMintTxn(repository, publicKey58, path1, name, Method.PUT, service, alice);

            // Create PATCH transaction
            Path path2 = Paths.get("src/test/resources/arbitrary/demo2");
            this.createAndMintTxn(repository, publicKey58, path2, name, Method.PATCH, service, alice);

            // Create another PATCH transaction
            Path path3 = Paths.get("src/test/resources/arbitrary/demo3");
            this.createAndMintTxn(repository, publicKey58, path3, name, Method.PATCH, service, alice);

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ResourceIdType.NAME, service);
            arbitraryDataReader.loadSynchronously(true);
            Path finalPath = arbitraryDataReader.getFilePath();

            // Ensure it exists
            assertTrue(Files.exists(finalPath));

            // Its directory hash should match the hash of demo3
            ArbitraryDataDigest path3Digest = new ArbitraryDataDigest(path3);
            path3Digest.compute();
            ArbitraryDataDigest finalPathDigest = new ArbitraryDataDigest(finalPath);
            finalPathDigest.compute();
            assertEquals(path3Digest.getHash58(), finalPathDigest.getHash58());

            // .. and its directory hash should also match the one included in the metadata
            ArbitraryDataMetadataPatch patchMetadata = new ArbitraryDataMetadataPatch(finalPath);
            patchMetadata.read();
            assertArrayEquals(patchMetadata.getCurrentHash(), path3Digest.getHash());

        }
    }

    @Test
    public void testPatchBeforePut() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            Service service = Service.WEBSITE; // Can be anything for this test

            // Create PATCH transaction, ensuring that an exception is thrown
            try {
                Path path1 = Paths.get("src/test/resources/arbitrary/demo1");
                this.createAndMintTxn(repository, publicKey58, path1, name, Method.PATCH, service, alice);
                fail("Creating transaction should fail due to nonexistent PUT transaction");

            } catch (DataException expectedException) {
                assertEquals(String.format("Unable to create arbitrary data file: Couldn't find PUT transaction for " +
                        "name %s and service %s", name, service), expectedException.getMessage());
            }

        }
    }

    @Test
    public void testNameDoesNotExist() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            Service service = Service.WEBSITE; // Can be anything for this test

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Create PUT transaction, ensuring that an exception is thrown
            try {
                Path path1 = Paths.get("src/test/resources/arbitrary/demo1");
                this.createAndMintTxn(repository, publicKey58, path1, name, Method.PUT, service, alice);
                fail("Creating transaction should fail due to the name being unregistered");

            } catch (DataException expectedException) {
                assertEquals("Arbitrary transaction invalid: NAME_DOES_NOT_EXIST", expectedException.getMessage());
            }
        }
    }

    @Test
    public void testUpdateResourceOwnedByAnotherCreator() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "TEST"; // Can be anything for this test
            Service service = Service.WEBSITE; // Can be anything for this test

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = Paths.get("src/test/resources/arbitrary/demo1");
            this.createAndMintTxn(repository, Base58.encode(alice.getPublicKey()), path1, name, Method.PUT, service, alice);

            // Bob attempts to update Alice's data
            PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

            // Create PATCH transaction, ensuring that an exception is thrown
            try {
                Path path2 = Paths.get("src/test/resources/arbitrary/demo2");
                this.createAndMintTxn(repository, Base58.encode(bob.getPublicKey()), path2, name, Method.PATCH, service, bob);
                fail("Creating transaction should fail due to the name being registered to Alice instead of Bob");

            } catch (DataException expectedException) {
                assertEquals("Arbitrary transaction invalid: INVALID_NAME_OWNER", expectedException.getMessage());
            }
        }
    }

    private void createAndMintTxn(Repository repository, String publicKey58, Path path, String name,
                                  Method method, Service service, PrivateKeyAccount account) throws DataException {

        ArbitraryDataTransactionBuilder txnBuilder = new ArbitraryDataTransactionBuilder(publicKey58, path, name, method, service);
        ArbitraryTransactionData transactionData = txnBuilder.build();
        Transaction.ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, account);
        assertEquals(Transaction.ValidationResult.OK, result);
        BlockUtils.mintBlock(repository);
    }

}
