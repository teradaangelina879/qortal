package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.metadata.ArbitraryDataMetadataCache;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;
import org.qortal.data.transaction.ArbitraryTransactionData.Service;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

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
    private byte[] latestSignature;
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
        this.validatePaths();
        this.findLatestSignature();
        this.buildLatestState();
        this.cacheLatestSignature();
    }

    private void fetchTransactions() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Get the most recent PUT
            ArbitraryTransactionData latestPut = repository.getArbitraryRepository()
                    .getLatestTransaction(this.name, this.service, Method.PUT);
            if (latestPut == null) {
                throw new IllegalStateException(String.format(
                        "Couldn't find PUT transaction for name %s and service %s", this.name, this.service));
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
            arbitraryDataReader.loadSynchronously(true);
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

    private void findLatestSignature() {
        if (this.transactions.size() == 0) {
            throw new IllegalStateException("Unable to find latest signature from empty transaction list");
        }

        // Find the latest signature
        ArbitraryTransactionData latestTransaction = this.transactions.get(this.transactions.size() - 1);
        if (latestTransaction == null) {
            throw new IllegalStateException("Unable to find latest signature from null transaction");
        }

        this.latestSignature = latestTransaction.getSignature();
    }

    private void validatePaths() {
        if (this.paths == null || this.paths.isEmpty()) {
            throw new IllegalStateException(String.format("No paths available from which to build latest state"));
        }
    }

    private void buildLatestState() throws IOException {
        if (this.paths.size() == 1) {
            // No patching needed
            this.finalPath = this.paths.get(0);
            return;
        }

        Path pathBefore = this.paths.get(0);

        // Loop from the second path onwards
        for (int i=1; i<paths.size(); i++) {
            LOGGER.info(String.format("[%s][%s] Applying layer %d...", this.service, this.name, i));
            Path pathAfter = this.paths.get(i);
            byte[] signatureBefore = this.transactions.get(i-1).getSignature();
            ArbitraryDataCombiner combiner = new ArbitraryDataCombiner(pathBefore, pathAfter, signatureBefore);
            combiner.combine();
            combiner.cleanup();
            pathBefore = combiner.getFinalPath();
        }
        this.finalPath = pathBefore;
    }

    private void cacheLatestSignature() throws IOException {
        byte[] latestTransactionSignature = this.transactions.get(this.transactions.size()-1).getSignature();
        if (latestTransactionSignature == null) {
            throw new IllegalStateException("Missing latest transaction signature");
        }

        ArbitraryDataMetadataCache cache = new ArbitraryDataMetadataCache(this.finalPath);
        cache.setSignature(latestTransactionSignature);
        cache.setTimestamp(NTP.getTime());
        cache.write();
    }

    public Path getFinalPath() {
        return this.finalPath;
    }

    public byte[] getLatestSignature() {
        return this.latestSignature;
    }

}
