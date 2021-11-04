package org.qortal.arbitrary;

import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.data.transaction.ArbitraryTransactionData.*;
import org.qortal.arbitrary.ArbitraryDataFile.*;
import org.qortal.repository.DataException;
import org.qortal.utils.NTP;

import java.io.IOException;

public class ArbitraryDataBuildQueueItem {

    private String resourceId;
    private ResourceIdType resourceIdType;
    private Service service;
    private Long creationTimestamp = null;
    private Long buildStartTimestamp = null;
    private Long buildEndTimestamp = null;
    private boolean failed = false;

    /* The maximum amount of time to spend on a single build */
    // TODO: interrupt an in-progress build
    public static long BUILD_TIMEOUT = 60*1000L; // 60 seconds
    /* The amount of time to remember that a build has failed, to avoid retries */
    public static long FAILURE_TIMEOUT = 5*60*1000L; // 5 minutes

    public ArbitraryDataBuildQueueItem(String resourceId, ResourceIdType resourceIdType, Service service) {
        this.resourceId = resourceId.toLowerCase();
        this.resourceIdType = resourceIdType;
        this.service = service;
        this.creationTimestamp = NTP.getTime();
    }

    public void build() throws IOException, DataException, MissingDataException {
        Long now = NTP.getTime();
        if (now == null) {
            throw new IllegalStateException("NTP time hasn't synced yet");
        }

        this.buildStartTimestamp = now;
        ArbitraryDataReader arbitraryDataReader =
                new ArbitraryDataReader(this.resourceId, this.resourceIdType, this.service);

        try {
            arbitraryDataReader.loadSynchronously(true);
        } finally {
            this.buildEndTimestamp = NTP.getTime();
        }
    }

    public boolean isBuilding() {
        return this.buildStartTimestamp != null;
    }

    public boolean isQueued() {
        return this.buildStartTimestamp == null;
    }

    public boolean hasReachedBuildTimeout(Long now) {
        if (now == null || this.creationTimestamp == null) {
            return true;
        }
        return now - this.creationTimestamp > BUILD_TIMEOUT;
    }

    public boolean hasReachedFailureTimeout(Long now) {
        if (now == null || this.buildStartTimestamp == null) {
            return true;
        }
        return now - this.buildStartTimestamp > FAILURE_TIMEOUT;
    }


    public String getResourceId() {
        return this.resourceId;
    }

    public Long getBuildStartTimestamp() {
        return this.buildStartTimestamp;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }


    @Override
    public String toString() {
        return String.format("%s %s", this.service, this.resourceId);
    }

}
