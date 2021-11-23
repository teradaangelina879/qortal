package org.qortal.arbitrary;

import org.qortal.api.model.ArbitraryResourceSummary;
import org.qortal.api.model.ArbitraryResourceSummary.ArbitraryResourceStatus;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.arbitrary.ArbitraryDataBuildManager;
import org.qortal.controller.arbitrary.ArbitraryDataManager;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.list.ResourceListManager;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.NTP;

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

        // Check if the name is blacklisted
        if (ResourceListManager.getInstance()
                .listContains("blacklist", "names", this.resourceId, false)) {
            return new ArbitraryResourceSummary(ArbitraryResourceStatus.BLACKLISTED);
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

        // Check if we have all data locally for this resource
        if (!this.allFilesDownloaded()) {
            if (this.isDataPotentiallyAvailable()) {
                return new ArbitraryResourceSummary(ArbitraryResourceStatus.DOWNLOADING);
            }
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
                if (!ArbitraryTransactionUtils.completeFileExists(transactionData) ||
                    !ArbitraryTransactionUtils.allChunksExist(transactionData)) {
                    return false;
                }
            }
            return true;

        } catch (DataException e) {
            return false;
        }
    }

    private boolean isRateLimited() {
        try {
            this.fetchTransactions();

            List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

            for (ArbitraryTransactionData transactionData : transactionDataList) {
                if (ArbitraryDataManager.getInstance().isSignatureRateLimited(transactionData.getSignature())) {
                    return true;
                }
            }
            return true;

        } catch (DataException e) {
            return false;
        }
    }

    /**
     * Best guess as to whether data might be available
     * This is only used to give an indication to the user of progress
     * @return - whether data might be available on the network
     */
    private boolean isDataPotentiallyAvailable() {
        try {
            this.fetchTransactions();
            Long now = NTP.getTime();
            if (now == null) {
                return false;
            }

            List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

            for (ArbitraryTransactionData transactionData : transactionDataList) {
                long lastRequestTime = ArbitraryDataManager.getInstance().lastRequestForSignature(transactionData.getSignature());
                // If we haven't requested yet, or requested in the last 30 seconds, there's still a
                // chance that data is on its way but hasn't arrived yet
                if (lastRequestTime == 0 || now - lastRequestTime < 30 * 1000L) {
                    return true;
                }
            }
            return false;

        } catch (DataException e) {
            return false;
        }
    }



    private void fetchTransactions() throws DataException {
        if (this.transactions != null && !this.transactions.isEmpty()) {
            // Already fetched
            return;
        }

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

    private String resourceIdString() {
        return resourceId != null ? resourceId : "";
    }

    private String resourceIdTypeString() {
        return resourceIdType != null ? resourceIdType.toString() : "";
    }

    private String serviceString() {
        return service != null ? service.toString() : "";
    }

    private String identifierString() {
        return identifier != null ? identifier : "";
    }

    public String toString() {
        return String.format("%s-%s-%s-%s", resourceIdString(), resourceIdTypeString(), serviceString(), identifierString());
    }
}
