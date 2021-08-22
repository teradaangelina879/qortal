package org.qortal.controller.pruning;

import org.qortal.controller.Controller;

import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.utils.DaemonThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PruneManager {

    private static PruneManager instance;

    private boolean pruningEnabled = Settings.getInstance().isPruningEnabled();
    private int pruneBlockLimit = Settings.getInstance().getPruneBlockLimit();
    private boolean builtLatestATStates = false;

    private PruneManager() {
        // Start individual pruning processes
        ExecutorService pruneExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory());
        pruneExecutor.execute(new AtStatesPruner());
        pruneExecutor.execute(new BlockPruner());
    }

    public static synchronized PruneManager getInstance() {
        if (instance == null)
            instance = new PruneManager();

        return instance;
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


    public void setBuiltLatestATStates(boolean builtLatestATStates) {
        this.builtLatestATStates = builtLatestATStates;
    }

    public boolean getBuiltLatestATStates() {
        return this.builtLatestATStates;
    }

}
