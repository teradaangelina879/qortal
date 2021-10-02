package org.qortal.repository;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.block.BlockData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.hsqldb.HSQLDBImportExport;
import org.qortal.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;
import org.qortal.utils.SevenZ;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;


public class Bootstrap {

    private final Repository repository;

    private static final Logger LOGGER = LogManager.getLogger(Bootstrap.class);

    /** The maximum number of untrimmed blocks allowed to be included in a bootstrap, beyond the trim threshold */
    private static final int MAXIMUM_UNTRIMMED_BLOCKS = 100;

    /** The maximum number of unpruned blocks allowed to be included in a bootstrap, beyond the prune threshold */
    private static final int MAXIMUM_UNPRUNED_BLOCKS = 100;


    public Bootstrap(Repository repository) {
        this.repository = repository;
    }

    /**
     * canBootstrap()
     * Performs basic initial checks to ensure everything is in order
     * @return true if ready for bootstrap creation, or false if not
     * All failure reasons are logged
     */
    public boolean canBootstrap() {
        try {
            LOGGER.info("Checking repository state...");

            final boolean pruningEnabled = Settings.getInstance().isPruningEnabled();
            final boolean archiveEnabled = Settings.getInstance().isArchiveEnabled();

            // Avoid creating bootstraps from pruned nodes until officially supported
            if (pruningEnabled) {
                LOGGER.info("Creating bootstraps from top-only nodes isn't yet supported.");
                // TODO: add support for top-only bootstraps
                return false;
            }

            // Require that a block archive has been built
            if (!archiveEnabled) {
                LOGGER.info("Unable to bootstrap because the block archive isn't enabled. " +
                        "Set {\"archivedEnabled\": true} in settings.json to fix.");
                return false;
            }

            // Make sure that the block archiver is up to date
            boolean upToDate = BlockArchiveWriter.isArchiverUpToDate(repository);
            if (!upToDate) {
                LOGGER.info("Unable to bootstrap because the block archive isn't fully built yet.");
                return false;
            }

            // Ensure that this database contains the ATStatesHeightIndex which was missing in some cases
            boolean hasAtStatesHeightIndex = repository.getATRepository().hasAtStatesHeightIndex();
            if (!hasAtStatesHeightIndex) {
                LOGGER.info("Unable to bootstrap due to missing ATStatesHeightIndex. A re-sync from genesis is needed.");
                return false;
            }

            // Ensure we have synced NTP time
            if (NTP.getTime() == null) {
                LOGGER.info("Unable to bootstrap because the node hasn't synced its time yet.");
                return false;
            }

            // Ensure the chain is synced
            final BlockData chainTip = Controller.getInstance().getChainTip();
            final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
            if (minLatestBlockTimestamp == null || chainTip.getTimestamp() < minLatestBlockTimestamp) {
                LOGGER.info("Unable to bootstrap because the blockchain isn't fully synced.");
                return false;
            }

            // FUTURE: ensure trim and prune settings are using default values

            // Ensure that the online account signatures have been fully trimmed
            final int accountsTrimStartHeight = repository.getBlockRepository().getOnlineAccountsSignaturesTrimHeight();
            final long accountsUpperTrimmableTimestamp = NTP.getTime() - BlockChain.getInstance().getOnlineAccountSignaturesMaxLifetime();
            final int accountsUpperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(accountsUpperTrimmableTimestamp);
            final int accountsBlocksRemaining = accountsUpperTrimmableHeight - accountsTrimStartHeight;
            if (accountsBlocksRemaining > MAXIMUM_UNTRIMMED_BLOCKS) {
                LOGGER.info("Blockchain is not fully trimmed. Please allow the node to run for longer, " +
                        "then try again. Blocks remaining (online accounts signatures): {}", accountsBlocksRemaining);
                return false;
            }

            // Ensure that the AT states data has been fully trimmed
            final int atTrimStartHeight = repository.getATRepository().getAtTrimHeight();
            final long atUpperTrimmableTimestamp = chainTip.getTimestamp() - Settings.getInstance().getAtStatesMaxLifetime();
            final int atUpperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(atUpperTrimmableTimestamp);
            final int atBlocksRemaining = atUpperTrimmableHeight - atTrimStartHeight;
            if (atBlocksRemaining > MAXIMUM_UNTRIMMED_BLOCKS) {
                LOGGER.info("Blockchain is not fully trimmed. Please allow the node to run for longer, " +
                        "then try again. Blocks remaining (AT states): {}", atBlocksRemaining);
                return false;
            }

            // Ensure that blocks have been fully pruned
            final int blockPruneStartHeight = repository.getBlockRepository().getBlockPruneHeight();
            int blockUpperPrunableHeight = chainTip.getHeight() - Settings.getInstance().getPruneBlockLimit();
            if (archiveEnabled) {
                blockUpperPrunableHeight = repository.getBlockArchiveRepository().getBlockArchiveHeight() - 1;
            }
            final int blocksPruneRemaining = blockUpperPrunableHeight - blockPruneStartHeight;
            if (blocksPruneRemaining > MAXIMUM_UNPRUNED_BLOCKS) {
                LOGGER.info("Blockchain is not fully pruned. Please allow the node to run for longer, " +
                        "then try again. Blocks remaining: {}", blocksPruneRemaining);
                return false;
            }

            // Ensure that AT states have been fully pruned
            final int atPruneStartHeight = repository.getATRepository().getAtPruneHeight();
            int atUpperPrunableHeight = chainTip.getHeight() - Settings.getInstance().getPruneBlockLimit();
            if (archiveEnabled) {
                atUpperPrunableHeight = repository.getBlockArchiveRepository().getBlockArchiveHeight() - 1;
            }
            final int atPruneRemaining = atUpperPrunableHeight - atPruneStartHeight;
            if (atPruneRemaining > MAXIMUM_UNPRUNED_BLOCKS) {
                LOGGER.info("Blockchain is not fully pruned. Please allow the node to run for longer, " +
                        "then try again. Blocks remaining (AT states): {}", atPruneRemaining);
                return false;
            }

            LOGGER.info("Repository state checks passed");
            return true;
        }
        catch (DataException e) {
            LOGGER.info("Unable to create bootstrap: {}", e.getMessage());
            return false;
        }
    }

    /**
     * validateBlockchain
     * Performs quick validation of recent blocks in blockchain, prior to creating a bootstrap
     * @return true if valid, false if not
     */
    public boolean validateBlockchain() {
        LOGGER.info("Validating blockchain...");

        try {
            BlockChain.validate();

            LOGGER.info("Blockchain is valid");

            return true;
        } catch (DataException e) {
            LOGGER.info("Blockchain validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * validateCompleteBlockchain
     * Performs intensive validation of all blocks in blockchain
     * @return true if valid, false if not
     */
    public boolean validateCompleteBlockchain() {
        LOGGER.info("Validating blockchain...");

        try {
            // Perform basic startup validation
            BlockChain.validate();

            // Perform more intensive full-chain validation
            BlockChain.validateAllBlocks();

            LOGGER.info("Blockchain is valid");

            return true;
        } catch (DataException e) {
            LOGGER.info("Blockchain validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String create() throws DataException, InterruptedException, IOException {

        LOGGER.info("Acquiring blockchain lock...");
        ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
        blockchainLock.lockInterruptibly();

        Path inputPath = null;
        Path outputPath = null;

        try {

            LOGGER.info("Exporting local data...");
            repository.exportNodeLocalData();

            LOGGER.info("Deleting trade bot states...");
            List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
            for (TradeBotData tradeBotData : allTradeBotData) {
                repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
            }

            LOGGER.info("Deleting minting accounts...");
            List<MintingAccountData> mintingAccounts = repository.getAccountRepository().getMintingAccounts();
            for (MintingAccountData mintingAccount : mintingAccounts) {
                repository.getAccountRepository().delete(mintingAccount.getPrivateKey());
            }

            repository.saveChanges();

            LOGGER.info("Creating bootstrap...");
            while (!Controller.isStopping()) {
                try {
                    // Timeout if the database isn't ready for backing up after 10 seconds
                    long timeout = 10 * 1000L;
                    repository.backup(false, "bootstrap", timeout);
                    break;
                }
                catch (TimeoutException e) {
                    LOGGER.info("Unable to create bootstrap due to timeout. Retrying...");
                }
            }

            LOGGER.info("Moving files to output directory...");
            inputPath = Paths.get(Settings.getInstance().getRepositoryPath(), "bootstrap");
            outputPath = Paths.get(Files.createTempDirectory("qortal-bootstrap").toString(), "bootstrap");


            // Move the db backup to a "bootstrap" folder in the root directory
            Files.move(inputPath, outputPath, REPLACE_EXISTING);

            // Copy the archive folder to inside the bootstrap folder
            FileUtils.copyDirectory(
                    Paths.get(Settings.getInstance().getRepositoryPath(), "archive").toFile(),
                    Paths.get(outputPath.toString(), "archive").toFile()
            );

            LOGGER.info("Compressing...");
            String compressedOutputPath = String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), this.getFilename());
            try {
                Files.delete(Paths.get(compressedOutputPath));
            } catch (NoSuchFileException e) {
                // Doesn't exist, so no need to delete
            }
            SevenZ.compress(compressedOutputPath, outputPath.toFile());

            // Return the path to the compressed bootstrap file
            Path finalPath = Paths.get(outputPath.toString(), compressedOutputPath);
            return finalPath.toAbsolutePath().toString();

        } finally {
            LOGGER.info("Re-importing local data...");
            Path exportPath = HSQLDBImportExport.getExportDirectory(false);
            repository.importDataFromFile(Paths.get(exportPath.toString(), "TradeBotStates.json").toString());
            repository.importDataFromFile(Paths.get(exportPath.toString(), "MintingAccounts.json").toString());

            blockchainLock.unlock();

            // Cleanup
            if (inputPath != null) {
                FileUtils.deleteDirectory(inputPath.toFile());
            }
            if (outputPath != null) {
                FileUtils.deleteDirectory(outputPath.toFile());
            }
        }
    }

    public void startImport() throws InterruptedException {
        while (!Controller.isStopping()) {
            try {
                LOGGER.info("Starting import of bootstrap...");

                this.doImport();

            } catch (DataException e) {
                LOGGER.info("Bootstrap import failed: {}", e.getMessage());
                LOGGER.info("Retrying in 5 minutes");
                Thread.sleep(5 * 60 * 1000L);
            }
        }
    }

    private void doImport() throws DataException {
        Path path = null;
        try {
            Path tempDir = Files.createTempDirectory("qortal-bootstrap");
            String filename = String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), this.getFilename());
            path = Paths.get(tempDir.toString(), filename);

            this.downloadToPath(path);
            this.importFromPath(path);

        } catch (InterruptedException | DataException | IOException e) {
            throw new DataException(String.format("Unable to import bootstrap: %s", e.getMessage()));
        }
        finally {
            if (path != null) {
                try {
                    FileUtils.deleteDirectory(path.toFile());

                } catch (IOException e) {
                    // Temp folder will be cleaned up by system anyway, so ignore this failure
                }
            }
        }
    }

    private String getFilename() {
        boolean pruningEnabled = Settings.getInstance().isPruningEnabled();
        boolean archiveEnabled = Settings.getInstance().isArchiveEnabled();

        if (pruningEnabled) {
            return "bootstrap-toponly.7z";
        }
        else if (archiveEnabled) {
            return "bootstrap-archive.7z";
        }
        else {
            return "bootstrap-full.7z";
        }
    }

    private void downloadToPath(Path path) throws DataException {
        // Select a random host from bootstrapHosts
        String[] hosts = Settings.getInstance().getBootstrapHosts();
        int index = new SecureRandom().nextInt(hosts.length);
        String bootstrapHost = hosts[index];
        String bootstrapFilename = this.getFilename();

        try {
            LOGGER.info("Downloading bootstrap...");
            String bootstrapUrl = String.format("%s/%s", bootstrapHost, bootstrapFilename);
            InputStream in = new URL(bootstrapUrl).openStream();
            Files.copy(in, path, REPLACE_EXISTING);

        } catch (IOException e) {
            throw new DataException(String.format("Unable to download bootstrap: {}", e.getMessage()));
        }
    }

    public void importFromPath(Path path) throws InterruptedException, DataException, IOException {

        ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
        blockchainLock.lockInterruptibly();

        try {
            LOGGER.info("Extracting bootstrap...");
            Path input = path.toAbsolutePath();
            Path output = path.toAbsolutePath().getParent().toAbsolutePath();
            SevenZ.decompress(input.toString(), output.toFile());

            LOGGER.info("Stopping repository...");
            // Close the repository while we are still able to
            // Otherwise, the caller will run into difficulties when it tries to close it
            repository.discardChanges();
            repository.close();
            // Now close the repository factory so that we can swap out the database files
            RepositoryManager.closeRepositoryFactory();

            Path inputPath = Paths.get(output.toString(), "bootstrap");
            Path outputPath = Paths.get(Settings.getInstance().getRepositoryPath());
            if (!inputPath.toFile().exists()) {
                throw new DataException("Extracted bootstrap doesn't exist");
            }

            // Move the "bootstrap" folder in place of the "db" folder
            LOGGER.info("Moving files to output directory...");
            FileUtils.deleteDirectory(outputPath.toFile());
            Files.move(inputPath, outputPath);

            LOGGER.info("Starting repository from bootstrap...");
            RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(Controller.getRepositoryUrl());
            RepositoryManager.setRepositoryFactory(repositoryFactory);

        }
        finally {
            blockchainLock.unlock();
        }
    }

}
