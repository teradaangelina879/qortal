package org.qortal.controller.arbitrary;

import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.list.ResourceListManager;
import org.qortal.settings.Settings;

public class ArbitraryDataStorageManager {

    public enum StoragePolicy {
        FOLLOWED_AND_VIEWED,
        FOLLOWED,
        VIEWED,
        ALL,
        NONE
    }

    private static ArbitraryDataStorageManager instance;

    public ArbitraryDataStorageManager() {
    }

    public static ArbitraryDataStorageManager getInstance() {
        if (instance == null)
            instance = new ArbitraryDataStorageManager();

        return instance;
    }

    public boolean canStoreData(ArbitraryTransactionData arbitraryTransactionData) {
        String name = arbitraryTransactionData.getName();

        // Don't store data unless it's an allowed type (public/private)
        if (!this.isDataTypeAllowed(arbitraryTransactionData)) {
            return false;
        }

        // Check if our storage policy and blacklist allows us to host data for this name
        switch (Settings.getInstance().getStoragePolicy()) {
            case FOLLOWED_AND_VIEWED:
            case ALL:
            case VIEWED:
                // If the policy includes viewed data, we can host it as long as it's not blacklisted
                return !this.isNameInBlacklist(name);

            case FOLLOWED:
                // If the policy is for followed data only, we have to be following it
                return this.isFollowingName(name);

                // For NONE or all else, we shouldn't host this data
            case NONE:
            default:
                return false;
        }
    }

    public boolean shouldPreFetchData(ArbitraryTransactionData arbitraryTransactionData) {
        String name = arbitraryTransactionData.getName();
        if (name == null) {
            return this.shouldPreFetchDataWithoutName(arbitraryTransactionData);
        }
        // Never fetch data from blacklisted names, even if they are followed
        if (this.isNameInBlacklist(name)) {
            return false;
        }
        // Don't store data unless it's an allowed type (public/private)
        if (!this.isDataTypeAllowed(arbitraryTransactionData)) {
            return false;
        }

        switch (Settings.getInstance().getStoragePolicy()) {
            case FOLLOWED:
            case FOLLOWED_AND_VIEWED:
                return this.isFollowingName(name);
                
            case ALL:
                return true;

            case NONE:
            case VIEWED:
            default:
                return false;
        }
    }

    private boolean shouldPreFetchDataWithoutName(ArbitraryTransactionData arbitraryTransactionData) {
        switch (Settings.getInstance().getStoragePolicy()) {
            case ALL:
                return this.isDataTypeAllowed(arbitraryTransactionData);

            case NONE:
            case VIEWED:
            case FOLLOWED:
            case FOLLOWED_AND_VIEWED:
            default:
                return false;
        }
    }

    private boolean isDataTypeAllowed(ArbitraryTransactionData arbitraryTransactionData) {
        byte[] secret = arbitraryTransactionData.getSecret();
        boolean hasSecret = (secret != null && secret.length == 32);

        if (!Settings.getInstance().isPrivateDataEnabled() && !hasSecret) {
            // Private data isn't enabled so we can't store data without a valid secret
            return false;
        }
        if (!Settings.getInstance().isPublicDataEnabled() && hasSecret) {
            // Public data isn't enabled so we can't store data with a secret
            return false;
        }
        return true;
    }

    public boolean isNameInBlacklist(String name) {
        return ResourceListManager.getInstance().listContains("blacklist", "names", name, false);
    }

    private boolean isFollowingName(String name) {
        return ResourceListManager.getInstance().listContains("followed", "names", name, false);
    }
}
