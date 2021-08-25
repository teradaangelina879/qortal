package org.qortal.controller.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;

public class BlockPruner implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(BlockPruner.class);

	@Override
	public void run() {
		Thread.currentThread().setName("Block pruner");

		if (!Settings.getInstance().isPruningEnabled()) {
			return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			int pruneStartHeight = repository.getBlockRepository().getBlockPruneHeight();

			while (!Controller.isStopping()) {
				repository.discardChanges();

				Thread.sleep(Settings.getInstance().getBlockPruneInterval());

				BlockData chainTip = Controller.getInstance().getChainTip();
				if (chainTip == null || NTP.getTime() == null)
					continue;

				// Don't even attempt if we're mid-sync as our repository requests will be delayed for ages
				if (Controller.getInstance().isSynchronizing())
					continue;

				// Prune all blocks up until our latest minus pruneBlockLimit
				final int ourLatestHeight = chainTip.getHeight();
				final int upperPrunableHeight = ourLatestHeight - Settings.getInstance().getPruneBlockLimit();

				int upperBatchHeight = pruneStartHeight + Settings.getInstance().getBlockPruneBatchSize();
				int upperPruneHeight = Math.min(upperBatchHeight, upperPrunableHeight);

				if (pruneStartHeight >= upperPruneHeight) {
					continue;
				}

				LOGGER.debug(String.format("Pruning blocks between %d and %d...", pruneStartHeight, upperPruneHeight));

				int numBlocksPruned = repository.getBlockRepository().pruneBlocks(pruneStartHeight, upperPruneHeight);
				repository.saveChanges();

				if (numBlocksPruned > 0) {
					final int finalPruneStartHeight = pruneStartHeight;
					LOGGER.debug(() -> String.format("Pruned %d block%s between %d and %d",
							numBlocksPruned, (numBlocksPruned != 1 ? "s" : ""),
							finalPruneStartHeight, upperPruneHeight));
				} else {
					// Can we move onto next batch?
					if (upperPrunableHeight > upperBatchHeight) {
						pruneStartHeight = upperBatchHeight;
						repository.getBlockRepository().setBlockPruneHeight(pruneStartHeight);
						repository.saveChanges();

						final int finalPruneStartHeight = pruneStartHeight;
						LOGGER.debug(() -> String.format("Bumping block base prune height to %d", finalPruneStartHeight));
					}
					else {
						// We've pruned up to the upper prunable height
						// Back off for a while to save CPU for syncing
						Thread.sleep(10*60*1000L);
					}
				}
			}
		} catch (DataException e) {
			LOGGER.warn(String.format("Repository issue trying to prune blocks: %s", e.getMessage()));
		} catch (InterruptedException e) {
			// Time to exit
		}
	}

}
