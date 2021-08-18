package org.qortal.arbitrary;

import org.qortal.data.transaction.ArbitraryTransactionData.*;
import org.qortal.arbitrary.ArbitraryDataFile.*;
import org.qortal.repository.DataException;
import org.qortal.utils.NTP;

import java.io.IOException;

public class ArbitraryDataBuildQueueItem {

    private String resourceId;
    private ResourceIdType resourceIdType;
    private Service service;
    private Long buildStartTimestamp = null;

    private static long BUILD_TIMEOUT = 60*1000L; // 60 seconds

    public ArbitraryDataBuildQueueItem(String resourceId, ResourceIdType resourceIdType, Service service) {
        this.resourceId = resourceId;
        this.resourceIdType = resourceIdType;
        this.service = service;
    }

    public void build() throws IOException, DataException {
        Long now = NTP.getTime();
        if (now == null) {
            throw new IllegalStateException("NTP time hasn't synced yet");
        }

        this.buildStartTimestamp = now;
        ArbitraryDataReader arbitraryDataReader =
                new ArbitraryDataReader(this.resourceId, this.resourceIdType, this.service);

        // We do not want to overwrite the existing cache, as this will be invalidated
        // automatically if new data has arrived
        arbitraryDataReader.loadSynchronously(false);
    }

    public boolean isBuilding() {
        return this.buildStartTimestamp != null;
    }

    public boolean isQueued() {
        return this.buildStartTimestamp == null;
    }

    public boolean hasReachedBuildTimeout(Long now) {
        if (now == null || this.buildStartTimestamp == null) {
            return true;
        }
        return now - this.buildStartTimestamp > BUILD_TIMEOUT;
    }


    public String getResourceId() {
        return this.resourceId;
    }

    public Long getBuildStartTimestamp() {
        return this.buildStartTimestamp;
    }

    @Override
    public String toString() {
        return String.format("%s %s", this.service, this.resourceId);
    }

}
