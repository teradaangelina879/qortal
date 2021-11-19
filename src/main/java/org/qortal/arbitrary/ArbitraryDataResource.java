package org.qortal.arbitrary;

import org.qortal.api.model.ArbitraryResourceSummary;
import org.qortal.api.model.ArbitraryResourceSummary.ArbitraryResourceStatus;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.arbitrary.ArbitraryDataBuildManager;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.list.ResourceListManager;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.ArbitraryTransactionUtils;

import java.util.ArrayList;
import java.util.List;

public class ArbitraryDataResource {

    private final String resourceId;
    private final ResourceIdType resourceIdType;
    private final Service service;
    private final String identifier;

    private List<ArbitraryTransactionData> transactions;
    private ArbitraryTransactionData latestPutTransaction;
    private int layerCount;

    public ArbitraryDataResource(String resourceId, ResourceIdType resourceIdType, Service service, String identifier) {
        this.resourceId = resourceId;
        this.resourceIdType = resourceIdType;
        this.service = service;
        this.identifier = identifier;
    }

    public ArbitraryResourceSummary getSummary() {
        if (resourceIdType != ResourceIdType.NAME) {
            // We only support statuses for resources with a name
            return new ArbitraryResourceSummary(ArbitraryResourceStatus.UNSUPPORTED);
        }

        // Firstly check the cache to see if it's already built
        ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(
                resourceId, resourceIdType, service, identifier);
        if (arbitraryDataReader.isCachedDataAvailable()) {
            return new ArbitraryResourceSummary(ArbitraryResourceStatus.READY);
        }

        // Next check if there's a build in progress
        ArbitraryDataBuildQueueItem queueItem =
                new ArbitraryDataBuildQueueItem(resourceId, resourceIdType, service, identifier);
        if (ArbitraryDataBuildManager.getInstance().isInBuildQueue(queueItem)) { // TODO: currently keyed by name only
            return new ArbitraryResourceSummary(ArbitraryResourceStatus.BUILDING);
        }

        // Check if a build has failed
        if (ArbitraryDataBuildManager.getInstance().isInFailedBuildsList(queueItem)) { // TODO: currently keyed by name only
            return new ArbitraryResourceSummary(ArbitraryResourceStatus.BUILD_FAILED);
        }

        // Check if the name is blacklisted
        if (ResourceListManager.getInstance()
                .listContains("blacklist", "names", this.resourceId, false)) {
            return new ArbitraryResourceSummary(ArbitraryResourceStatus.BLACKLISTED);
        }

        // Check if we have all data locally for this resource
        if (!this.allFilesDownloaded()) {
            return new ArbitraryResourceSummary(ArbitraryResourceStatus.MISSING_DATA);
        }

        // We have all data locally
        return new ArbitraryResourceSummary(ArbitraryResourceStatus.DOWNLOADED);
    }

    private boolean allFilesDownloaded() {
        try {
            this.fetchTransactions();

            List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

            for (ArbitraryTransactionData transactionData : transactionDataList) {
                if (!ArbitraryTransactionUtils.allChunksExist(transactionData)) {
                    return false;
                }
            }
            return true;

        } catch (DataException e) {
            return false;
        }
    }

    private void fetchTransactions() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Get the most recent PUT
            ArbitraryTransactionData latestPut = repository.getArbitraryRepository()
                    .getLatestTransaction(this.resourceId, this.service, ArbitraryTransactionData.Method.PUT, this.identifier);
            if (latestPut == null) {
                String message = String.format("Couldn't find PUT transaction for name %s, service %s and identifier %s",
                        this.resourceId, this.service, this.identifierString());
                throw new DataException(message);
            }
            this.latestPutTransaction = latestPut;

            // Load all transactions since the latest PUT
            List<ArbitraryTransactionData> transactionDataList = repository.getArbitraryRepository()
                    .getArbitraryTransactions(this.resourceId, this.service, this.identifier, latestPut.getTimestamp());

            this.transactions = transactionDataList;
            this.layerCount = transactionDataList.size();
        }
    }

    private String identifierString() {
        return identifier != null ? identifier : "";
    }
}
