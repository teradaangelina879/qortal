package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.*;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.ArbitraryTransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ArbitraryDataTransactionBuilder {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataTransactionBuilder.class);

    private String publicKey58;
    private Path path;
    private String name;
    private Method method;
    private Service service;

    public ArbitraryDataTransactionBuilder(String publicKey58, Path path, String name, Method method, Service service) {
        this.publicKey58 = publicKey58;
        this.path = path;
        this.name = name;
        this.method = method;
        this.service = service;
    }

    public ArbitraryTransactionData build() throws DataException {
        ArbitraryDataFile arbitraryDataFile = null;
        try (final Repository repository = RepositoryManager.getRepository()) {

            if (publicKey58 == null || path == null) {
                throw new DataException("Missing public key or path");
            }
            byte[] creatorPublicKey = Base58.decode(publicKey58);
            final String creatorAddress = Crypto.toAddress(creatorPublicKey);
            byte[] lastReference = repository.getAccountRepository().getLastReference(creatorAddress);
            if (lastReference == null) {
                // Use a random last reference on the very first transaction for an account
                // Code copied from CrossChainResource.buildAtMessage()
                // We already require PoW on all arbitrary transactions, so no additional logic is needed
                Random random = new Random();
                lastReference = new byte[Transformer.SIGNATURE_LENGTH];
                random.nextBytes(lastReference);
            }

            ArbitraryTransactionData.Compression compression = ArbitraryTransactionData.Compression.ZIP;

            ArbitraryDataWriter arbitraryDataWriter = new ArbitraryDataWriter(path, name, service, method, compression);
            try {
                arbitraryDataWriter.save();
            } catch (IOException | DataException | InterruptedException | RuntimeException e) {
                LOGGER.info("Unable to create arbitrary data file: {}", e.getMessage());
                throw new DataException(String.format("Unable to create arbitrary data file: %s", e.getMessage()));
            }

            arbitraryDataFile = arbitraryDataWriter.getArbitraryDataFile();
            if (arbitraryDataFile == null) {
                throw new DataException("Arbitrary data file is null");
            }

            String digest58 = arbitraryDataFile.digest58();
            if (digest58 == null) {
                LOGGER.error("Unable to calculate file digest");
                throw new DataException("Unable to calculate file digest");
            }

            final BaseTransactionData baseTransactionData = new BaseTransactionData(NTP.getTime(), Group.NO_GROUP,
                    lastReference, creatorPublicKey, BlockChain.getInstance().getUnitFee(), null);
            final int size = (int) arbitraryDataFile.size();
            final int version = 5;
            final int nonce = 0;
            byte[] secret = arbitraryDataFile.getSecret();
            final ArbitraryTransactionData.DataType dataType = ArbitraryTransactionData.DataType.DATA_HASH;
            final byte[] digest = arbitraryDataFile.digest();
            final byte[] chunkHashes = arbitraryDataFile.chunkHashes();
            final List<PaymentData> payments = new ArrayList<>();

            ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
                    version, service, nonce, size, name, method,
                    secret, compression, digest, dataType, chunkHashes, payments);

            ArbitraryTransaction transaction = (ArbitraryTransaction) Transaction.fromData(repository, transactionData);
            LOGGER.info("Computing nonce...");
            transaction.computeNonce();

            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            if (result != Transaction.ValidationResult.OK) {
                arbitraryDataFile.deleteAll();
                throw new DataException(String.format("Arbitrary transaction invalid: %s", result));
            }
            LOGGER.info("Transaction is valid");

            return transactionData;

        } catch (DataException e) {
            if (arbitraryDataFile != null) {
                arbitraryDataFile.deleteAll();
            }
            throw(e);
        }

    }

}
