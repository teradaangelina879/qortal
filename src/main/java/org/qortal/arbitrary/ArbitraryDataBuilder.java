package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;
import org.qortal.data.transaction.ArbitraryTransactionData.Service;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ArbitraryDataBuilder {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataBuilder.class);

    private String name;
    private Service service;

    private List<ArbitraryTransactionData> transactions;
    private ArbitraryTransactionData latestPutTransaction;
    private List<Path> paths;
    private Path finalPath;

    public ArbitraryDataBuilder(String name, Service service) {
        this.name = name;
        this.service = service;
        this.paths = new ArrayList<>();
    }

    public void build() throws DataException, IOException {
        this.fetchTransactions();
        this.validateTransactions();
        this.processTransactions();
        this.buildLatestState();
    }

    private void fetchTransactions() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Get the most recent PUT
            ArbitraryTransactionData latestPut = repository.getArbitraryRepository()
                    .getLatestTransaction(this.name, this.service, Method.PUT);
            if (latestPut == null) {
                throw new IllegalStateException("Cannot PATCH without existing PUT. Deploy using PUT first.");
            }
            this.latestPutTransaction = latestPut;

            // Load all transactions since the latest PUT
            List<ArbitraryTransactionData> transactionDataList = repository.getArbitraryRepository()
                    .getArbitraryTransactions(this.name, this.service, latestPut.getTimestamp());
            this.transactions = transactionDataList;
        }
    }

    private void validateTransactions() {
        List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);
        ArbitraryTransactionData latestPut = this.latestPutTransaction;

        if (latestPut == null) {
            throw new IllegalStateException("Cannot PATCH without existing PUT. Deploy using PUT first.");
        }
        if (latestPut.getMethod() != Method.PUT) {
            throw new IllegalStateException("Expected PUT but received PATCH");
        }
        if (transactionDataList.size() == 0) {
            throw new IllegalStateException(String.format("No transactions found for name %s, service %s, since %d",
                    name, service, latestPut.getTimestamp()));
        }

        // Verify that the signature of the first transaction matches the latest PUT
        ArbitraryTransactionData firstTransaction = transactionDataList.get(0);
        if (!Objects.equals(firstTransaction.getSignature(), latestPut.getSignature())) {
            throw new IllegalStateException("First transaction did not match latest PUT transaction");
        }

        // Remove the first transaction, as it should be the only PUT
        transactionDataList.remove(0);

        for (ArbitraryTransactionData transactionData : transactionDataList) {
            if (!(transactionData instanceof ArbitraryTransactionData)) {
                String sig58 = Base58.encode(transactionData.getSignature());
                throw new IllegalStateException(String.format("Received non-arbitrary transaction: %s", sig58));
            }
            if (transactionData.getMethod() != Method.PATCH) {
                throw new IllegalStateException("Expected PATCH but received PUT");
            }
        }
    }

    private void processTransactions() throws IOException, DataException {
        List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

        for (ArbitraryTransactionData transactionData : transactionDataList) {
            LOGGER.trace("Found arbitrary transaction {}", Base58.encode(transactionData.getSignature()));

            // Build the data file, overwriting anything that was previously there
            String sig58 = Base58.encode(transactionData.getSignature());
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(sig58, ResourceIdType.TRANSACTION_DATA, this.service);
            arbitraryDataReader.setTransactionData(transactionData);
            arbitraryDataReader.load(true);
            Path path = arbitraryDataReader.getFilePath();
            if (path == null) {
                throw new IllegalStateException(String.format("Null path when building data from transaction %s", sig58));
            }
            if (!Files.exists(path)) {
                throw new IllegalStateException(String.format("Path doesn't exist when building data from transaction %s", sig58));
            }
            paths.add(path);
        }
    }

    private void buildLatestState() throws IOException, DataException {
        ArbitraryDataPatches arbitraryDataPatches = new ArbitraryDataPatches(this.paths);
        arbitraryDataPatches.applyPatches();
        this.finalPath = arbitraryDataPatches.getFinalPath();
    }

    public Path getFinalPath() {
        return this.finalPath;
    }

}
