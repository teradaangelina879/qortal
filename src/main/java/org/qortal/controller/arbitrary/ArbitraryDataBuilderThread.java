package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataBuildQueueItem;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.controller.Controller;
import org.qortal.repository.DataException;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.util.Map;


public class ArbitraryDataBuilderThread implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataBuilderThread.class);

    public ArbitraryDataBuilderThread() {

    }

    public void run() {
        Thread.currentThread().setName("Arbitrary Data Build Manager");
        ArbitraryDataBuildManager buildManager = ArbitraryDataBuildManager.getInstance();

        while (!Controller.isStopping()) {
            try {
                Thread.sleep(1000);

                if (buildManager.arbitraryDataBuildQueue == null) {
                    continue;
                }
                if (buildManager.arbitraryDataBuildQueue.isEmpty()) {
                    continue;
                }

                // Find resources that are queued for building
                Map.Entry<String, ArbitraryDataBuildQueueItem> next = buildManager.arbitraryDataBuildQueue
                        .entrySet().stream()
                        .filter(e -> e.getValue().isQueued())
                        .findFirst().orElse(null);

                if (next == null) {
                    continue;
                }

                Long now = NTP.getTime();
                if (now == null) {
                    continue;
                }

                ArbitraryDataBuildQueueItem queueItem = next.getValue();

                if (queueItem == null) {
                    this.removeFromQueue(queueItem);
                }

                // Ignore builds that have failed recently
                if (buildManager.isInFailedBuildsList(queueItem)) {
                    continue;
                }


                try {
                    // Perform the build
                    LOGGER.info("Building {}...", queueItem);
                    queueItem.build();
                    this.removeFromQueue(queueItem);
                    LOGGER.info("Finished building {}", queueItem);

                } catch (MissingDataException e) {
                    LOGGER.info("Missing data for {}: {}", queueItem, e.getMessage());
                    queueItem.setFailed(true);
                    this.removeFromQueue(queueItem);
                    // Don't add to the failed builds list, as we may want to retry sooner

                } catch (IOException | DataException | RuntimeException e) {
                    LOGGER.info("Error building {}: {}", queueItem, e.getMessage());
                    // Something went wrong - so remove it from the queue, and add to failed builds list
                    queueItem.setFailed(true);
                    buildManager.addToFailedBuildsList(queueItem);
                    this.removeFromQueue(queueItem);
                }

            } catch (InterruptedException e) {
                // Time to exit
            }
        }
    }

    private void removeFromQueue(ArbitraryDataBuildQueueItem queueItem) {
        if (queueItem == null || queueItem.getUniqueKey() == null) {
            return;
        }
        ArbitraryDataBuildManager.getInstance().arbitraryDataBuildQueue.remove(queueItem.getUniqueKey());
    }
}
