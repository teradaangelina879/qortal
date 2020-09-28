package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.BlockChain;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.NTP;

public class OnlineAccountsSignaturesTrimmer implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(OnlineAccountsSignaturesTrimmer.class);

	private enum TrimMode { SEARCHING, TRIMMING }
	private static final long TRIM_INTERVAL = 2 * 1000L; // ms
	private static final int TRIM_SEARCH_SIZE = 5000; // blocks
	private static final int TRIM_BATCH_SIZE = 500; // blocks

	private TrimMode trimMode = TrimMode.SEARCHING;
	private int trimStartHeight = 0;

	public void run() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			while (!Controller.isStopping()) {
				repository.discardChanges();

				Thread.sleep(TRIM_INTERVAL);

				BlockData chainTip = Controller.getInstance().getChainTip();
				if (chainTip == null || NTP.getTime() == null)
					continue;

				// Trim blockchain by removing 'old' online accounts signatures
				long upperTrimmableTimestamp = NTP.getTime() - BlockChain.getInstance().getOnlineAccountSignaturesMaxLifetime();
				int upperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(upperTrimmableTimestamp);

				if (trimMode == TrimMode.SEARCHING) {
					int trimEndHeight = Math.min(trimStartHeight + TRIM_SEARCH_SIZE, upperTrimmableHeight);

					LOGGER.debug(() -> String.format("Searching for trimmable online accounts signatures between blocks %d and %d", trimStartHeight, trimEndHeight));
					int foundStartHeight = repository.getBlockRepository().findFirstTrimmableOnlineAccountsSignatureHeight(trimStartHeight, trimEndHeight);

					if (foundStartHeight == 0) {
						// No trimmable online accounts signatures found
						trimStartHeight = trimEndHeight;
					} else {
						trimStartHeight = foundStartHeight;
						trimMode = TrimMode.TRIMMING;
						LOGGER.debug(() -> String.format("Found first trimmable online accounts signatures at block height %d", trimStartHeight));
					}

					// The above search will probably take enough time by itself so wait until next round 
					continue;
				}

				int upperBatchHeight = Math.min(trimStartHeight + TRIM_BATCH_SIZE, upperTrimmableHeight);

				if (trimStartHeight >= upperBatchHeight)
					continue;

				int numSigsTrimmed = repository.getBlockRepository().trimOldOnlineAccountsSignatures(trimStartHeight, upperBatchHeight);
				repository.saveChanges();

				if (numSigsTrimmed > 0) {
					LOGGER.debug(() -> String.format("Trimmed %d online accounts signature%s between blocks %d and %d",
							numSigsTrimmed, (numSigsTrimmed != 1 ? "s" : ""),
							trimStartHeight, upperBatchHeight));
				} else {
					trimStartHeight = upperBatchHeight;
				}
			}
		} catch (DataException e) {
			LOGGER.warn(String.format("Repository issue trying to trim online accounts signatures: %s", e.getMessage()));
		} catch (InterruptedException e) {
			// Time to exit
		}
	}

}
