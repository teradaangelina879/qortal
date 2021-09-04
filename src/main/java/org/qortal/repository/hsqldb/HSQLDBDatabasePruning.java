package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockData;
import org.qortal.repository.BlockArchiveWriter;
import org.qortal.repository.DataException;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 *
 * When switching from a full node to a pruning node, we need to delete most of the database contents.
 * If we do this entirely as a background process, it is very slow and can interfere with syncing.
 * However, if we take the approach of transferring only the necessary rows to a new table and then
 * deleting the original table, this makes the process much faster. It was taking several days to
 * delete the AT states in the background, but only a couple of minutes to copy them to a new table.
 *
 * The trade off is that we have to go through a form of "reshape" when starting the app for the first
 * time after enabling pruning mode. But given that this is an opt-in mode, I don't think it will be
 * a problem.
 *
 * Once the pruning is complete, it automatically performs a CHECKPOINT DEFRAG in order to
 * shrink the database file size down to a fraction of what it was before.
 *
 * From this point, the original background process will run, but can be dialled right down so not
 * to interfere with syncing.
 *
 */


public class HSQLDBDatabasePruning {

    private static final Logger LOGGER = LogManager.getLogger(HSQLDBDatabasePruning.class);


    public static boolean pruneATStates() throws SQLException, DataException {
        try (final HSQLDBRepository repository = (HSQLDBRepository)RepositoryManager.getRepository()) {

            // Only bulk prune AT states if we have never done so before
            int pruneHeight = repository.getATRepository().getAtPruneHeight();
            if (pruneHeight > 0) {
                // Already pruned AT states
                return false;
            }

            if (Settings.getInstance().isArchiveEnabled()) {
                // Only proceed if we can see that the archiver has already finished
                // This way, if the archiver failed for any reason, we can prune once it has had
                // some opportunities to try again
                boolean upToDate = BlockArchiveWriter.isArchiverUpToDate(repository, false);
                if (!upToDate) {
                    return false;
                }
            }

            LOGGER.info("Starting bulk prune of AT states - this process could take a while... " +
                    "(approx. 2 mins on high spec, or upwards of 30 mins in some cases)");

            // Create new AT-states table to hold smaller dataset
            repository.executeCheckedUpdate("DROP TABLE IF EXISTS ATStatesNew");
            repository.executeCheckedUpdate("CREATE TABLE ATStatesNew ("
                    + "AT_address QortalAddress, height INTEGER NOT NULL, state_hash ATStateHash NOT NULL, "
                    + "fees QortalAmount NOT NULL, is_initial BOOLEAN NOT NULL, sleep_until_message_timestamp BIGINT, "
                    + "PRIMARY KEY (AT_address, height), "
                    + "FOREIGN KEY (AT_address) REFERENCES ATs (AT_address) ON DELETE CASCADE)");
            repository.executeCheckedUpdate("SET TABLE ATStatesNew NEW SPACE");
            repository.executeCheckedUpdate("CHECKPOINT");


            // Find our latest block
            BlockData latestBlock = repository.getBlockRepository().getLastBlock();
            if (latestBlock == null) {
                LOGGER.info("Unable to determine blockchain height, necessary for bulk block pruning");
                return false;
            }

            // Calculate some constants for later use
            final int blockchainHeight = latestBlock.getHeight();
            int maximumBlockToTrim = blockchainHeight - Settings.getInstance().getPruneBlockLimit();
            if (Settings.getInstance().isArchiveEnabled()) {
                // Archive mode - don't prune anything that hasn't been archived yet
                maximumBlockToTrim = Math.min(maximumBlockToTrim, repository.getBlockArchiveRepository().getBlockArchiveHeight() - 1);
            }
            final int startHeight = maximumBlockToTrim;
            final int endHeight = blockchainHeight;
            final int blockStep = 10000;



            // Loop through all the LatestATStates and copy them to the new table
            LOGGER.info("Copying AT states...");
            for (int height = 0; height < endHeight; height += blockStep) {
                //LOGGER.info(String.format("Copying AT states between %d and %d...", height, height + blockStep - 1));

                String sql = "SELECT height, AT_address FROM LatestATStates WHERE height BETWEEN ? AND ?";
                try (ResultSet latestAtStatesResultSet = repository.checkedExecute(sql, height, height + blockStep - 1)) {
                    if (latestAtStatesResultSet != null) {
                        do {
                            int latestAtHeight = latestAtStatesResultSet.getInt(1);
                            String latestAtAddress = latestAtStatesResultSet.getString(2);

                            // Copy this latest ATState to the new table
                            //LOGGER.info(String.format("Copying AT %s at height %d...", latestAtAddress, latestAtHeight));
                            try {
                                String updateSql = "INSERT INTO ATStatesNew ("
                                        + "SELECT AT_address, height, state_hash, fees, is_initial, sleep_until_message_timestamp "
                                        + "FROM ATStates "
                                        + "WHERE height = ? AND AT_address = ?)";
                                repository.executeCheckedUpdate(updateSql, latestAtHeight, latestAtAddress);
                            } catch (SQLException e) {
                                repository.examineException(e);
                                throw new DataException("Unable to copy ATStates", e);
                            }

                            if (height >= startHeight) {
                                // Now copy this AT's states for each recent block they is present in
                                for (int i = startHeight; i < endHeight; i++) {
                                    if (latestAtHeight < i) {
                                        // This AT finished before this block so there is nothing to copy
                                        continue;
                                    }

                                    //LOGGER.info(String.format("Copying recent AT %s at height %d...", latestAtAddress, i));
                                    try {
                                        // Copy each LatestATState to the new table
                                        String updateSql = "INSERT IGNORE INTO ATStatesNew ("
                                                + "SELECT AT_address, height, state_hash, fees, is_initial, sleep_until_message_timestamp "
                                                + "FROM ATStates "
                                                + "WHERE height = ? AND AT_address = ?)";
                                        repository.executeCheckedUpdate(updateSql, i, latestAtAddress);
                                    } catch (SQLException e) {
                                        repository.examineException(e);
                                        throw new DataException("Unable to copy ATStates", e);
                                    }
                                }
                            }

                        } while (latestAtStatesResultSet.next());
                    }
                } catch (SQLException e) {
                    throw new DataException("Unable to copy AT states", e);
                }
            }

            repository.saveChanges();

            // Add a height index
            LOGGER.info("Rebuilding AT states height index in repository");
            repository.executeCheckedUpdate("CREATE INDEX IF NOT EXISTS ATStatesHeightIndex ON ATStatesNew (height)");
            repository.executeCheckedUpdate("CHECKPOINT");

            // Finally, drop the original table and rename
            LOGGER.info("Deleting old AT states...");
            repository.executeCheckedUpdate("DROP TABLE ATStates");
            repository.executeCheckedUpdate("ALTER TABLE ATStatesNew RENAME TO ATStates");
            repository.executeCheckedUpdate("CHECKPOINT");

            // Update the prune height
            repository.getATRepository().setAtPruneHeight(maximumBlockToTrim);
            repository.saveChanges();

            repository.executeCheckedUpdate("CHECKPOINT");

            // Now prune/trim the ATStatesData, as this currently goes back over a month
            return HSQLDBDatabasePruning.pruneATStateData();
        }
    }

    /*
     * Bulk prune ATStatesData to catch up with the now pruned ATStates table
     * This uses the existing AT States trimming code but with a much higher end block
     */
    private static boolean pruneATStateData() throws SQLException, DataException {
        try (final HSQLDBRepository repository = (HSQLDBRepository) RepositoryManager.getRepository()) {

            if (Settings.getInstance().isArchiveEnabled()) {
                // Don't prune ATStatesData in archive mode
                return true;
            }

            BlockData latestBlock = repository.getBlockRepository().getLastBlock();
            if (latestBlock == null) {
                LOGGER.info("Unable to determine blockchain height, necessary for bulk ATStatesData pruning");
                return false;
            }
            final int blockchainHeight = latestBlock.getHeight();
            int upperPrunableHeight = blockchainHeight - Settings.getInstance().getPruneBlockLimit();
            // ATStateData is already trimmed - so carry on from where we left off in the past
            int pruneStartHeight = repository.getATRepository().getAtTrimHeight();

            LOGGER.info("Starting bulk prune of AT states data - this process could take a while... (approx. 3 mins on high spec)");

            while (pruneStartHeight < upperPrunableHeight) {
                // Prune all AT state data up until our latest minus pruneBlockLimit (or our archive height)

                if (Controller.isStopping()) {
                    return false;
                }

                // Override batch size in the settings because this is a one-off process
                final int batchSize = 1000;
                final int rowLimitPerBatch = 50000;
                int upperBatchHeight = pruneStartHeight + batchSize;
                int upperPruneHeight = Math.min(upperBatchHeight, upperPrunableHeight);

                LOGGER.trace(String.format("Pruning AT states data between %d and %d...", pruneStartHeight, upperPruneHeight));

                int numATStatesPruned = repository.getATRepository().trimAtStates(pruneStartHeight, upperPruneHeight, rowLimitPerBatch);
                repository.saveChanges();

                if (numATStatesPruned > 0) {
                    final int finalPruneStartHeight = pruneStartHeight;
                    LOGGER.trace(() -> String.format("Pruned %d AT states data rows between blocks %d and %d",
                            numATStatesPruned, finalPruneStartHeight, upperPruneHeight));
                } else {
                    // Can we move onto next batch?
                    if (upperPrunableHeight > upperBatchHeight) {
                        pruneStartHeight = upperBatchHeight;
                        repository.getATRepository().setAtTrimHeight(pruneStartHeight);
                        // No need to rebuild the latest AT states as we aren't currently synchronizing
                        repository.saveChanges();

                        final int finalPruneStartHeight = pruneStartHeight;
                        LOGGER.debug(() -> String.format("Bumping AT states trim height to %d", finalPruneStartHeight));
                    }
                    else {
                        // We've finished pruning
                        break;
                    }
                }
            }

            return true;
        }
    }

    public static boolean pruneBlocks() throws SQLException, DataException {
        try (final HSQLDBRepository repository = (HSQLDBRepository) RepositoryManager.getRepository()) {

            // Only bulk prune AT states if we have never done so before
            int pruneHeight = repository.getBlockRepository().getBlockPruneHeight();
            if (pruneHeight > 0) {
                // Already pruned blocks
                return false;
            }

            if (Settings.getInstance().isArchiveEnabled()) {
                // Only proceed if we can see that the archiver has already finished
                // This way, if the archiver failed for any reason, we can prune once it has had
                // some opportunities to try again
                boolean upToDate = BlockArchiveWriter.isArchiverUpToDate(repository, false);
                if (!upToDate) {
                    return false;
                }
            }

            BlockData latestBlock = repository.getBlockRepository().getLastBlock();
            if (latestBlock == null) {
                LOGGER.info("Unable to determine blockchain height, necessary for bulk block pruning");
                return false;
            }
            final int blockchainHeight = latestBlock.getHeight();
            int upperPrunableHeight = blockchainHeight - Settings.getInstance().getPruneBlockLimit();
            int pruneStartHeight = 0;

            if (Settings.getInstance().isArchiveEnabled()) {
                // Archive mode - don't prune anything that hasn't been archived yet
                upperPrunableHeight = Math.min(upperPrunableHeight, repository.getBlockArchiveRepository().getBlockArchiveHeight() - 1);
            }

            LOGGER.info("Starting bulk prune of blocks - this process could take a while... (approx. 5 mins on high spec)");

            while (pruneStartHeight < upperPrunableHeight) {
                // Prune all blocks up until our latest minus pruneBlockLimit

                int upperBatchHeight = pruneStartHeight + Settings.getInstance().getBlockPruneBatchSize();
                int upperPruneHeight = Math.min(upperBatchHeight, upperPrunableHeight);

                LOGGER.info(String.format("Pruning blocks between %d and %d...", pruneStartHeight, upperPruneHeight));

                int numBlocksPruned = repository.getBlockRepository().pruneBlocks(pruneStartHeight, upperPruneHeight);
                repository.saveChanges();

                if (numBlocksPruned > 0) {
                    final int finalPruneStartHeight = pruneStartHeight;
                    LOGGER.info(() -> String.format("Pruned %d block%s between %d and %d",
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
                        // We've finished pruning
                        break;
                    }
                }
            }

            return true;
        }
    }

    public static void performMaintenance() throws SQLException, DataException {
        try (final HSQLDBRepository repository = (HSQLDBRepository) RepositoryManager.getRepository()) {
            repository.performPeriodicMaintenance();
        }
    }

}
