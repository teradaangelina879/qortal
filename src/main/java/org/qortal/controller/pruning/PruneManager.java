package org.qortal.controller.pruning;

import org.qortal.controller.AtStatesTrimmer;
import org.qortal.controller.Controller;

import org.qortal.controller.OnlineAccountsSignaturesTrimmer;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.utils.DaemonThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PruneManager {

    private static PruneManager instance;

    private boolean pruningEnabled = Settings.getInstance().isPruningEnabled();
    private int pruneBlockLimit = Settings.getInstance().getPruneBlockLimit();

    private ExecutorService executorService;

    private PruneManager() {

    }

    public static synchronized PruneManager getInstance() {
        if (instance == null)
            instance = new PruneManager();

        return instance;
    }

    public void start() {
        this.executorService = Executors.newCachedThreadPool(new DaemonThreadFactory());

        // Don't allow both the pruner and the trimmer to run at the same time.
        // In pruning mode, we are already deleting far more than we would when trimming.
        // In non-pruning mode, we still need to trim to keep the non-essential data
        // out of the database. There isn't a case where both are needed at once.
        // If we ever do need to enable both at once, be very careful with the AT state
        // trimming, since both currently rely on having exclusive access to the
        // prepareForAtStateTrimming() method. For both trimming and pruning to take place
        // at once, we would need to synchronize this method in a way that both can't
        // call it at the same time, as otherwise active ATs would be pruned/trimmed when
        // they should have been kept.

        if (Settings.getInstance().isPruningEnabled()) {
            // Pruning enabled - start the pruning processes
            this.executorService.execute(new AtStatesPruner());
            this.executorService.execute(new BlockPruner());
        }
        else {
            // Pruning disabled - use trimming instead
            this.executorService.execute(new AtStatesTrimmer());
            this.executorService.execute(new OnlineAccountsSignaturesTrimmer());
        }
    }

    public void stop() {
        this.executorService.shutdownNow();

        try {
            this.executorService.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // We tried...
        }
    }

    public boolean isBlockPruned(int height, Repository repository) throws DataException {
        if (!this.pruningEnabled) {
            return false;
        }

        BlockData chainTip = Controller.getInstance().getChainTip();
        if (chainTip == null) {
            throw new DataException("Unable to determine chain tip when checking if a block is pruned");
        }

        final int ourLatestHeight = chainTip.getHeight();
        final int latestUnprunedHeight = ourLatestHeight - this.pruneBlockLimit;

        return (height < latestUnprunedHeight);
    }

}
