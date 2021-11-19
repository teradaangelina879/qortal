package org.qortal.arbitrary;

import org.qortal.api.model.ArbitraryResourceSummary;
import org.qortal.api.model.ArbitraryResourceSummary.ArbitraryResourceStatus;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.arbitrary.ArbitraryDataBuildManager;
import org.qortal.list.ResourceListManager;
import org.qortal.repository.DataException;

import java.io.IOException;

public class ArbitraryDataResource {

    private final String resourceId;
    private final ResourceIdType resourceIdType;
    private final Service service;
    private final String identifier;

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
        ArbitraryDataBuilder builder = new ArbitraryDataBuilder(resourceId, service, identifier);
        builder.setCanRequestMissingFiles(false);
        try {
            builder.process();

        } catch (MissingDataException e) {
            return new ArbitraryResourceSummary(ArbitraryResourceStatus.DOWNLOADING);

        } catch (IOException | DataException e) {
            // Ignore for now
        }

        // FUTURE: support DOWNLOADED state once the build queue system has been upgraded

        return new ArbitraryResourceSummary(ArbitraryResourceStatus.NOT_STARTED);
    }
}
