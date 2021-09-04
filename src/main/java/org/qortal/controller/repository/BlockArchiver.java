package org.qortal.controller.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockData;
import org.qortal.repository.*;
import org.qortal.settings.Settings;
import org.qortal.transform.TransformationException;
import org.qortal.utils.NTP;

import java.io.IOException;

public class BlockArchiver implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(BlockArchiver.class);

	private static final long INITIAL_SLEEP_PERIOD = 0L; // TODO: 5 * 60 * 1000L + 1234L; // ms

	public void run() {
		Thread.currentThread().setName("Block archiver");

		if (!Settings.getInstance().isArchiveEnabled()) {
			return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			int startHeight = repository.getBlockArchiveRepository().getBlockArchiveHeight();

			// Don't even start building until initial rush has ended
			Thread.sleep(INITIAL_SLEEP_PERIOD);

			LOGGER.info("Starting block archiver...");

			while (!Controller.isStopping()) {
				repository.discardChanges();

				final int maximumArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository, true);

				Thread.sleep(Settings.getInstance().getArchiveInterval());

				BlockData chainTip = Controller.getInstance().getChainTip();
				if (chainTip == null || NTP.getTime() == null) {
					continue;
				}

				// Don't even attempt if we're mid-sync as our repository requests will be delayed for ages
				if (Controller.getInstance().isSynchronizing()) {
					continue;
				}

				// Don't attempt to archive if we're not synced yet
				final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
				if (minLatestBlockTimestamp == null || chainTip.getTimestamp() < minLatestBlockTimestamp) {
					continue;
				}


				// Build cache of blocks
				try {
					BlockArchiveWriter writer = new BlockArchiveWriter(startHeight, maximumArchiveHeight, repository);
					BlockArchiveWriter.BlockArchiveWriteResult result = writer.write();
					switch (result) {
						case OK:
							// Increment block archive height
							startHeight += writer.getWrittenCount();
							repository.getBlockArchiveRepository().setBlockArchiveHeight(startHeight);
							repository.saveChanges();
							break;

						case STOPPING:
							return;

						case BLOCK_LIMIT_REACHED:
							// We've reached the limit of the blocks we can archive
							// Sleep for a while to allow more to become available
						case NOT_ENOUGH_BLOCKS:
							// We didn't reach our file size target, so that must mean that we don't have enough blocks
							// yet or something went wrong. Sleep for a while and then try again.
							Thread.sleep(60 * 60 * 1000L); // 1 hour
							break;

						case BLOCK_NOT_FOUND:
							// We tried to archive a block that didn't exist. This is a major failure and likely means
							// that a bootstrap or re-sync is needed. Try again every minute until then.
							LOGGER.info("Error: block not found when building archive. If this error persists, " +
									"a bootstrap or re-sync may be needed.");
							Thread.sleep( 60 * 1000L); // 1 minute
							break;
					}

				} catch (IOException | TransformationException e) {
					LOGGER.info("Caught exception when creating block cache", e);
				}

			}
		} catch (DataException e) {
			LOGGER.info("Caught exception when creating block cache", e);
		} catch (InterruptedException e) {
			// Do nothing
		}

	}

}
