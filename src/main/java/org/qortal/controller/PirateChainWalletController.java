package org.qortal.controller;

import com.rust.litewalletjni.LiteWalletJni;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.PirateWallet;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.util.Objects;

public class PirateChainWalletController extends Thread {

    protected static final Logger LOGGER = LogManager.getLogger(PirateChainWalletController.class);

    private static PirateChainWalletController instance;

    final private static long SAVE_INTERVAL = 60 * 60 * 1000L; // 1 hour
    private long lastSaveTime = 0L;

    private boolean running;
    private PirateWallet currentWallet = null;


    private PirateChainWalletController() {
        this.running = true;
    }

    public static PirateChainWalletController getInstance() {
        if (instance == null)
            instance = new PirateChainWalletController();

        return instance;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Pirate Chain Wallet Controller");

        LiteWalletJni.loadLibrary();

        try {
            while (running && !Controller.isStopping()) {
                Thread.sleep(1000);

                if (this.currentWallet == null) {
                    // Nothing to do yet
                    continue;
                }

                LOGGER.debug("Syncing Pirate Chain wallet...");
                String response = LiteWalletJni.execute("sync", "");
                LOGGER.debug("sync response: {}", response);
                JSONObject json = new JSONObject(response);
                if (json.has("result")) {
                    String result = json.getString("result");

                    // We may have to set wallet to ready if this is the first ever successful sync
                    if (Objects.equals(result, "success")) {
                        this.currentWallet.setReady(true);
                    }
                }

                // Rate limit sync attempts
                Thread.sleep(60000);

                // Save wallet if needed
                Long now = NTP.getTime();
                if (now != null && now-SAVE_INTERVAL >= this.lastSaveTime) {
                    this.saveCurrentWallet();
                }
            }
        } catch (InterruptedException e) {
            // Fall-through to exit
        }
    }

    public void shutdown() {
        // Save the wallet
        this.saveCurrentWallet();

        this.running = false;
        this.interrupt();
    }


    public boolean initWithEntropy58(String entropy58) {
        return this.initWithEntropy58(entropy58, false);
    }

    public boolean initNullSeedWallet() {
        return this.initWithEntropy58(Base58.encode(new byte[32]), true);
    }

    private boolean initWithEntropy58(String entropy58, boolean isNullSeedWallet) {
        byte[] entropyBytes = Base58.decode(entropy58);

        if (entropyBytes == null || entropyBytes.length != 32) {
            LOGGER.info("Invalid entropy bytes");
            return false;
        }

        if (this.currentWallet != null) {
            if (this.currentWallet.entropyBytesEqual(entropyBytes)) {
                // Wallet already active - nothing to do
                return true;
            }
            else {
                // Different wallet requested - close the existing one and switch over
                this.closeCurrentWallet();
            }
        }

        try {
            this.currentWallet = new PirateWallet(entropyBytes, isNullSeedWallet);
            if (!this.currentWallet.isReady()) {
                // Don't persist wallets that aren't ready
                this.currentWallet = null;
            }
            return true;
        } catch (IOException e) {
            LOGGER.info("Unable to initialize wallet: {}", e.getMessage());
        }

        return false;
    }

    private void saveCurrentWallet() {
        if (this.currentWallet == null) {
            // Nothing to do
            return;
        }
        try {
            if (this.currentWallet.save()) {
                Long now = NTP.getTime();
                if (now != null) {
                    this.lastSaveTime = now;
                }
            }
        } catch (IOException e) {
            LOGGER.info("Unable to save wallet");
        }
    }

    public PirateWallet getCurrentWallet() {
        return this.currentWallet;
    }

    private void closeCurrentWallet() {
        this.saveCurrentWallet();
        this.currentWallet = null;
    }

    public void ensureInitialized() throws ForeignBlockchainException {
        if (!LiteWalletJni.isLoaded() || this.currentWallet == null || !this.currentWallet.isInitialized()) {
            throw new ForeignBlockchainException("Pirate wallet isn't initialized yet");
        }
    }

    public void ensureNotNullSeed() throws ForeignBlockchainException {
        // Safety check to make sure funds aren't sent to a null seed wallet
        if (this.currentWallet == null || this.currentWallet.isNullSeedWallet()) {
            throw new ForeignBlockchainException("Invalid wallet");
        }
    }

    public void ensureSynchronized() throws ForeignBlockchainException {
        if (this.currentWallet == null || !this.currentWallet.isSynchronized()) {
            throw new ForeignBlockchainException("Wallet isn't synchronized yet");
        }

        String response = LiteWalletJni.execute("syncStatus", "");
        JSONObject json = new JSONObject(response);
        if (json.has("syncing")) {
            boolean isSyncing = Boolean.valueOf(json.getString("syncing"));
            if (isSyncing) {
                long syncedBlocks = json.getLong("synced_blocks");
                long totalBlocks = json.getLong("total_blocks");

                throw new ForeignBlockchainException(String.format("Sync in progress (%d / %d). Please try again later.", syncedBlocks, totalBlocks));
            }
        }
    }

    public String getSyncStatus() {
        // TODO: check library exists, and show status of download if not

        if (this.currentWallet == null || !this.currentWallet.isInitialized()) {
            return "Not initialized yet";
        }

        String syncStatusResponse = LiteWalletJni.execute("syncStatus", "");
        org.json.JSONObject json = new JSONObject(syncStatusResponse);
        if (json.has("syncing")) {
            boolean isSyncing = Boolean.valueOf(json.getString("syncing"));
            if (isSyncing) {
                long syncedBlocks = json.getLong("synced_blocks");
                long totalBlocks = json.getLong("total_blocks");
                return String.format("Sync in progress (%d / %d)", syncedBlocks, totalBlocks);
            }
        }

        boolean isSynchronized = this.currentWallet.isSynchronized();
        if (isSynchronized) {
            return "Synchronized";
        }

        return "Initializing wallet...";
    }

}
