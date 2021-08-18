package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataBuildQueueItem;
import org.qortal.controller.Controller;
import org.qortal.repository.DataException;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.util.Map;


public class ArbitraryDataBuildManager implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataBuildManager.class);

    public ArbitraryDataBuildManager() {

    }

    public void run() {
        Thread.currentThread().setName("Arbitrary Data Build Manager");
        ArbitraryDataManager arbitraryDataManager = ArbitraryDataManager.getInstance();

        while (!Controller.isStopping()) {
            try {
                Thread.sleep(1000);

                if (arbitraryDataManager.arbitraryDataBuildQueue == null) {
                    continue;
                }
                if (arbitraryDataManager.arbitraryDataBuildQueue.isEmpty()) {
                    continue;
                }

                // Find resources that are queued for building
                Map.Entry<String, ArbitraryDataBuildQueueItem> next = arbitraryDataManager.arbitraryDataBuildQueue
                        .entrySet().stream()
                        .filter(e -> e.getValue().isQueued())
                        .findFirst().get();

                if (next == null) {
                    continue;
                }

                Long now = NTP.getTime();
                if (now == null) {
                    continue;
                }

                String resourceId = next.getKey();
                ArbitraryDataBuildQueueItem queueItem = next.getValue();

                if (queueItem == null) {
                    this.removeFromQueue(resourceId);
                }

                // Ignore builds that have failed recently
                if (ArbitraryDataManager.getInstance().isInFailedBuildsList(queueItem)) {
                    continue;
                }


                try {
                    // Perform the build
                    LOGGER.info("Building {}...", queueItem);
                    queueItem.build();
                    this.removeFromQueue(resourceId);
                    LOGGER.info("Finished building {}", queueItem);

                } catch (IOException | DataException | RuntimeException e) {
                    LOGGER.info("Error building {}: {}", queueItem, e.getMessage());
                    // Something went wrong - so remove it from the queue, and add to failed builds list
                    queueItem.setFailed(true);
                    ArbitraryDataManager.getInstance().addToFailedBuildsList(queueItem);
                    this.removeFromQueue(resourceId);
                }

            } catch (InterruptedException e) {
                // Time to exit
            }
        }
    }

    private void removeFromQueue(String resourceId) {
        ArbitraryDataManager.getInstance().arbitraryDataBuildQueue.remove(resourceId);
    }
}
