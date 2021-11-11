package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.metadata.ArbitraryDataMetadataCache;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;
import org.qortal.data.transaction.ArbitraryTransactionData.Service;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.settings.Settings;
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
    private String identifier;

    private List<ArbitraryTransactionData> transactions;
    private ArbitraryTransactionData latestPutTransaction;
    private List<Path> paths;
    private byte[] latestSignature;
    private Path finalPath;

    public ArbitraryDataBuilder(String name, Service service, String identifier) {
        this.name = name;
        this.service = service;
        this.identifier = identifier;
        this.paths = new ArrayList<>();
    }

    public void build() throws DataException, IOException, MissingDataException {
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
                    .getLatestTransaction(this.name, this.service, Method.PUT, this.identifier);
            if (latestPut == null) {
                String message = String.format("Couldn't find PUT transaction for name %s, service %s and identifier %s",
                        this.name, this.service, this.identifierString());
                throw new IllegalStateException(message);
            }
            this.latestPutTransaction = latestPut;

            // Load all transactions since the latest PUT
            List<ArbitraryTransactionData> transactionDataList = repository.getArbitraryRepository()
                    .getArbitraryTransactions(this.name, this.service, this.identifier, latestPut.getTimestamp());
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
            throw new IllegalStateException(String.format("No transactions found for name %s, service %s, " +
                            "identifier: %s, since %d", name, service, this.identifierString(), latestPut.getTimestamp()));
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

    private void processTransactions() throws IOException, DataException, MissingDataException {
        List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

        int count = 0;
        for (ArbitraryTransactionData transactionData : transactionDataList) {
            LOGGER.trace("Found arbitrary transaction {}", Base58.encode(transactionData.getSignature()));
            count++;

            // Build the data file, overwriting anything that was previously there
            String sig58 = Base58.encode(transactionData.getSignature());
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(sig58, ResourceIdType.TRANSACTION_DATA,
                    this.service, this.identifier);
            arbitraryDataReader.setTransactionData(transactionData);
            boolean hasMissingData = false;
            try {
                arbitraryDataReader.loadSynchronously(true);
            }
            catch (MissingDataException e) {
                hasMissingData = true;
            }

            // Handle missing data
            if (hasMissingData) {
                if (count == transactionDataList.size()) {
                    // This is the final transaction in the list, so we need to fail
                    throw new MissingDataException("Requesting missing files. Please wait and try again.");
                }
                // There are more transactions, so we should process them to give them the opportunity to request data
                continue;
            }

            // By this point we should have all data needed to build the layers
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

    private void buildLatestState() throws IOException, DataException {
        if (this.paths.size() == 1) {
            // No patching needed
            this.finalPath = this.paths.get(0);
            return;
        }

        Path pathBefore = this.paths.get(0);
        boolean validateAllLayers = Settings.getInstance().shouldValidateAllDataLayers();

        // Loop from the second path onwards
        for (int i=1; i<paths.size(); i++) {
            String identifierPrefix = this.identifier != null ? String.format("[%s]", this.identifier) : "";
            LOGGER.info(String.format("[%s][%s]%s Applying layer %d...", this.service, this.name, identifierPrefix, i));

            // Create an instance of ArbitraryDataCombiner
            Path pathAfter = this.paths.get(i);
            byte[] signatureBefore = this.transactions.get(i-1).getSignature();
            ArbitraryDataCombiner combiner = new ArbitraryDataCombiner(pathBefore, pathAfter, signatureBefore);

            // We only want to validate this layer's hash if it's the final layer, or if the settings
            // indicate that we should validate interim layers too
            boolean isFinalLayer = (i == paths.size() - 1);
            combiner.setShouldValidateHashes(isFinalLayer || validateAllLayers);

            // Now combine this layer with the last, and set the output path to the "before" path for the next cycle
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

    private String identifierString() {
        return identifier != null ? identifier : "";
    }

    public Path getFinalPath() {
        return this.finalPath;
    }

    public byte[] getLatestSignature() {
        return this.latestSignature;
    }

}
