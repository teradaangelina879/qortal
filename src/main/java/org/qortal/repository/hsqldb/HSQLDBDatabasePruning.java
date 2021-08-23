package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.block.BlockData;
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

            LOGGER.info("Starting bulk prune of AT states - this process could take a while... (approx. 2 mins on high spec)");

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
            final int maximumBlockToTrim = blockchainHeight - Settings.getInstance().getPruneBlockLimit();
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
                                // Now copy this AT states for each recent block it is present in
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

            BlockData latestBlock = repository.getBlockRepository().getLastBlock();
            if (latestBlock == null) {
                LOGGER.info("Unable to determine blockchain height, necessary for bulk block pruning");
                return false;
            }
            final int blockchainHeight = latestBlock.getHeight();
            final int upperPrunableHeight = blockchainHeight - Settings.getInstance().getPruneBlockLimit();
            int pruneStartHeight = 0;

            LOGGER.info("Starting bulk prune of blocks - this process could take a while... (approx. 10 mins on high spec)");

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
