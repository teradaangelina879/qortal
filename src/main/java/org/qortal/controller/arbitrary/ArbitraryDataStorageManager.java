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

    public boolean shouldStoreDataForName(String name) {
        if (name == null) {
            return this.shouldStoreDataWithoutName();
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

    private boolean shouldStoreDataWithoutName() {
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
