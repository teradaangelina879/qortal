package org.qortal.controller.arbitrary;

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

    public boolean canStoreDataForName(String name) {
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

    public boolean isNameInBlacklist(String name) {
        return ResourceListManager.getInstance().listContains("blacklist", "names", name);
    }

    public boolean shouldPreFetchDataForName(String name) {
        if (name == null) {
            return this.shouldPreFetchDataWithoutName();
        }
        // Never fetch data from blacklisted names, even if they are followed
        if (this.isNameInBlacklist(name)) {
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

    private boolean shouldPreFetchDataWithoutName() {
        switch (Settings.getInstance().getStoragePolicy()) {
            case ALL:
                return true;

            case NONE:
            case VIEWED:
            case FOLLOWED:
            case FOLLOWED_AND_VIEWED:
            default:
                return false;
        }
    }

    private boolean isFollowingName(String name) {
        return ResourceListManager.getInstance().listContains("followed", "names", name);
    }
}
