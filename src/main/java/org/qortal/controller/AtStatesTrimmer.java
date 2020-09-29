package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;

public class AtStatesTrimmer implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(AtStatesTrimmer.class);

	private enum TrimMode { SEARCHING, TRIMMING }
	private static final long TRIM_INTERVAL = 2 * 1000L; // ms
	private static final int TRIM_SEARCH_SIZE = 2000; // blocks
	private static final int TRIM_BATCH_SIZE = 200; // blocks
	private static final int TRIM_LIMIT = 4000; // rows

	private TrimMode trimMode = TrimMode.SEARCHING;
	private int trimStartHeight = 0;

	@Override
	public void run() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			while (!Controller.isStopping()) {
				repository.discardChanges();

				Thread.sleep(TRIM_INTERVAL);

				BlockData chainTip = Controller.getInstance().getChainTip();
				if (chainTip == null || NTP.getTime() == null)
					continue;

				long currentTrimmableTimestamp = NTP.getTime() - Settings.getInstance().getAtStatesMaxLifetime();
				// We want to keep AT states near the tip of our copy of blockchain so we can process/orphan nearby blocks
				long chainTrimmableTimestamp = chainTip.getTimestamp() - Settings.getInstance().getAtStatesMaxLifetime();

				long upperTrimmableTimestamp = Math.min(currentTrimmableTimestamp, chainTrimmableTimestamp);
				int upperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(upperTrimmableTimestamp);

				if (trimMode == TrimMode.SEARCHING) {
					int trimEndHeight = Math.min(trimStartHeight + TRIM_SEARCH_SIZE, upperTrimmableHeight);

					LOGGER.debug(() -> String.format("Searching for trimmable AT states between blocks %d and %d", trimStartHeight, trimEndHeight));
					int foundStartHeight = repository.getATRepository().findFirstTrimmableStateHeight(trimStartHeight, trimEndHeight);

					if (foundStartHeight == 0) {
						// No trimmable AT states found
						trimStartHeight = trimEndHeight;
					} else {
						trimStartHeight = foundStartHeight;
						trimMode = TrimMode.TRIMMING;
						LOGGER.debug(() -> String.format("Found first trimmable AT state at block height %d", trimStartHeight));
					}

					// The above search will probably take enough time by itself so wait until next round 
					continue;
				}

				int upperBatchHeight = Math.min(trimStartHeight + TRIM_BATCH_SIZE, upperTrimmableHeight);

				if (trimStartHeight >= upperBatchHeight)
					continue;

				int numAtStatesTrimmed = repository.getATRepository().trimAtStates(trimStartHeight, upperBatchHeight, TRIM_LIMIT);
				repository.saveChanges();

				if (numAtStatesTrimmed > 0) {
					LOGGER.debug(() -> String.format("Trimmed %d AT state%s between blocks %d and %d",
							numAtStatesTrimmed, (numAtStatesTrimmed != 1 ? "s" : ""),
							trimStartHeight, upperBatchHeight));
				} else {
					trimStartHeight = upperBatchHeight;
				}
			}
		} catch (DataException e) {
			LOGGER.warn(String.format("Repository issue trying to trim AT states: %s", e.getMessage()));
		} catch (InterruptedException e) {
			// Time to exit
		}
	}

}
