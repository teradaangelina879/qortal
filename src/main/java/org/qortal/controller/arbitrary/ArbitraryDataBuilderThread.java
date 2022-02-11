package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataBuildQueueItem;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.controller.Controller;
import org.qortal.repository.DataException;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.util.Comparator;
import java.util.Map;


public class ArbitraryDataBuilderThread implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataBuilderThread.class);

    public ArbitraryDataBuilderThread() {

    }

    @Override
    public void run() {
        Thread.currentThread().setName("Arbitrary Data Builder Thread");
        ArbitraryDataBuildManager buildManager = ArbitraryDataBuildManager.getInstance();

        while (!Controller.isStopping()) {
            try {
                Thread.sleep(100);

                if (buildManager.arbitraryDataBuildQueue == null) {
                    continue;
                }
                if (buildManager.arbitraryDataBuildQueue.isEmpty()) {
                    continue;
                }

                Long now = NTP.getTime();
                if (now == null) {
                    continue;
                }

                ArbitraryDataBuildQueueItem queueItem = null;

                // Find resources that are queued for building (sorted by highest priority first)
                synchronized (buildManager.arbitraryDataBuildQueue) {
                    Map.Entry<String, ArbitraryDataBuildQueueItem> next = buildManager.arbitraryDataBuildQueue
                            .entrySet().stream()
                            .filter(e -> e.getValue().isQueued())
                            .sorted(Comparator.comparing(item -> item.getValue().getPriority()))
                            .reduce((first, second) -> second).orElse(null);

                    if (next == null) {
                        continue;
                    }

                    queueItem = next.getValue();

                    if (queueItem == null) {
                        this.removeFromQueue(queueItem);
                        continue;
                    }

                    // Ignore builds that have failed recently
                    if (buildManager.isInFailedBuildsList(queueItem)) {
                        this.removeFromQueue(queueItem);
                        continue;
                    }

                    // Set the start timestamp, to prevent other threads from building it at the same time
                    queueItem.prepareForBuild();
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
