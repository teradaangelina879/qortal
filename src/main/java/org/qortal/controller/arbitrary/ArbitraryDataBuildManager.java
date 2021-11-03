package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataBuildQueueItem;
import org.qortal.utils.NTP;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArbitraryDataBuildManager extends Thread {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataBuildManager.class);

    private static ArbitraryDataBuildManager instance;

    private volatile boolean isStopping = false;
    private boolean buildInProgress = false;

    /**
     * Map to keep track of arbitrary transaction resources currently being built (or queued).
     */
    public Map<String, ArbitraryDataBuildQueueItem> arbitraryDataBuildQueue = Collections.synchronizedMap(new HashMap<>());

    /**
     * Map to keep track of failed arbitrary transaction builds.
     */
    public Map<String, ArbitraryDataBuildQueueItem> arbitraryDataFailedBuilds = Collections.synchronizedMap(new HashMap<>());


    public ArbitraryDataBuildManager() {

    }

    @Override
    public void run() {
        try {
            // Use a fixed thread pool to execute the arbitrary data build actions (currently just a single thread)
            // This can be expanded to have multiple threads processing the build queue when needed
            ExecutorService arbitraryDataBuildExecutor = Executors.newFixedThreadPool(1);
            arbitraryDataBuildExecutor.execute(new ArbitraryDataBuilderThread());

            while (!isStopping) {
                // Nothing to do yet
                Thread.sleep(5000);
            }

        } catch (InterruptedException e) {
            // Fall-through to exit thread...
        }
    }

    public static ArbitraryDataBuildManager getInstance() {
        if (instance == null)
            instance = new ArbitraryDataBuildManager();

        return instance;
    }

    public void shutdown() {
        isStopping = true;
        this.interrupt();
    }


    public void cleanupQueues(Long now) {
        if (now == null) {
            return;
        }
        arbitraryDataBuildQueue.entrySet().removeIf(entry -> entry.getValue().hasReachedBuildTimeout(now));
        arbitraryDataFailedBuilds.entrySet().removeIf(entry -> entry.getValue().hasReachedFailureTimeout(now));
    }

    // Build queue

    public boolean addToBuildQueue(ArbitraryDataBuildQueueItem queueItem) {
        String resourceId = queueItem.getResourceId();
        if (resourceId == null) {
            return false;
        }
        resourceId = resourceId.toLowerCase();

        if (this.arbitraryDataBuildQueue == null) {
            return false;
        }

        if (NTP.getTime() == null) {
            // Can't use queues until we have synced the time
            return false;
        }

        // Don't add builds that have failed recently
        if (this.isInFailedBuildsList(queueItem)) {
            return false;
        }

        if (this.arbitraryDataBuildQueue.put(resourceId, queueItem) != null) {
            // Already in queue
            return true;
        }

        LOGGER.info("Added {} to build queue", resourceId);

        // Added to queue
        return true;
    }

    public boolean isInBuildQueue(ArbitraryDataBuildQueueItem queueItem) {
        String resourceId = queueItem.getResourceId();
        if (resourceId == null) {
            return false;
        }

        if (this.arbitraryDataBuildQueue == null) {
            return false;
        }

        if (this.arbitraryDataBuildQueue.containsKey(resourceId)) {
            // Already in queue
            return true;
        }

        // Not in queue
        return false;
    }


    // Failed builds

    public boolean addToFailedBuildsList(ArbitraryDataBuildQueueItem queueItem) {
        String resourceId = queueItem.getResourceId();
        if (resourceId == null) {
            return false;
        }

        if (this.arbitraryDataFailedBuilds == null) {
            return false;
        }

        if (NTP.getTime() == null) {
            // Can't use queues until we have synced the time
            return false;
        }

        if (this.arbitraryDataFailedBuilds.put(resourceId, queueItem) != null) {
            // Already in list
            return true;
        }

        LOGGER.info("Added {} to failed builds list", resourceId);

        // Added to queue
        return true;
    }

    public boolean isInFailedBuildsList(ArbitraryDataBuildQueueItem queueItem) {
        String resourceId = queueItem.getResourceId();
        if (resourceId == null) {
            return false;
        }
        resourceId = resourceId.toLowerCase();

        if (this.arbitraryDataFailedBuilds == null) {
            return false;
        }

        if (this.arbitraryDataFailedBuilds.containsKey(resourceId)) {
            // Already in list
            return true;
        }

        // Not in list
        return false;
    }


    public void setBuildInProgress(boolean buildInProgress) {
        this.buildInProgress = buildInProgress;
    }

    public boolean getBuildInProgress() {
        return this.buildInProgress;
    }
}
