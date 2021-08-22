package org.qortal.controller.pruning;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;

public class AtStatesPruner implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(AtStatesPruner.class);

	@Override
	public void run() {
		Thread.currentThread().setName("AT States pruner");

		if (!Settings.getInstance().isPruningEnabled()) {
			return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			int pruneStartHeight = repository.getATRepository().getAtPruneHeight();

			// repository.getATRepository().prepareForAtStatePruning();
			// repository.saveChanges();

			while (!Controller.isStopping()) {
				repository.discardChanges();

				Thread.sleep(Settings.getInstance().getAtStatesPruneInterval());

				if (PruneManager.getInstance().getBuiltLatestATStates() == false) {
					// Wait for latest AT states table to be built first
					// This has a dependency on the AtStatesTrimmer running,
					// which should be okay, given that it isn't something
					// is disabled in normal operation.
					continue;
				}

				BlockData chainTip = Controller.getInstance().getChainTip();
				if (chainTip == null || NTP.getTime() == null)
					continue;

				// Don't even attempt if we're mid-sync as our repository requests will be delayed for ages
				if (Controller.getInstance().isSynchronizing())
					continue;

				long currentPrunableTimestamp = NTP.getTime() - Settings.getInstance().getAtStatesMaxLifetime();
				// We want to keep AT states near the tip of our copy of blockchain so we can process/orphan nearby blocks
				long chainPrunableTimestamp = chainTip.getTimestamp() - Settings.getInstance().getAtStatesMaxLifetime();

				long upperPrunableTimestamp = Math.min(currentPrunableTimestamp, chainPrunableTimestamp);
				int upperPrunableHeight = repository.getBlockRepository().getHeightFromTimestamp(upperPrunableTimestamp);

				int upperBatchHeight = pruneStartHeight + Settings.getInstance().getAtStatesPruneBatchSize();
				int upperPruneHeight = Math.min(upperBatchHeight, upperPrunableHeight);

				if (pruneStartHeight >= upperPruneHeight)
					continue;

				LOGGER.debug(String.format("Pruning AT states between blocks %d and %d...", pruneStartHeight, upperPruneHeight));

				int numAtStatesPruned = repository.getATRepository().pruneAtStates(pruneStartHeight, upperPruneHeight);
				repository.saveChanges();

				if (numAtStatesPruned > 0) {
					final int finalPruneStartHeight = pruneStartHeight;
					LOGGER.debug(() -> String.format("Pruned %d AT state%s between blocks %d and %d",
							numAtStatesPruned, (numAtStatesPruned != 1 ? "s" : ""),
							finalPruneStartHeight, upperPruneHeight));
				} else {
					// Can we move onto next batch?
					if (upperPrunableHeight > upperBatchHeight) {
						pruneStartHeight = upperBatchHeight;
						repository.getATRepository().setAtPruneHeight(pruneStartHeight);
						repository.getATRepository().prepareForAtStatePruning();
						repository.saveChanges();

						final int finalPruneStartHeight = pruneStartHeight;
						LOGGER.debug(() -> String.format("Bumping AT state base prune height to %d", finalPruneStartHeight));
					}
				}
			}
		} catch (DataException e) {
			LOGGER.warn(String.format("Repository issue trying to prune AT states: %s", e.getMessage()));
		} catch (InterruptedException e) {
			// Time to exit
		}
	}

}
