package org.qortal.list;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

public class ResourceListManager {

    private static final Logger LOGGER = LogManager.getLogger(ResourceListManager.class);

    private static ResourceListManager instance;
    private ResourceList addressBlacklist;

    public ResourceListManager() {
        try {
            this.addressBlacklist = new ResourceList("blacklist", "address");
        } catch (IOException e) {
            LOGGER.info("Error while loading address blacklist. Blocking is currently unavailable.");
        }
    }

    public static synchronized ResourceListManager getInstance() {
        if (instance == null) {
            instance = new ResourceListManager();
        }

        return instance;
    }

    public boolean addAddressToBlacklist(String address, boolean save) {
        try {
            this.addressBlacklist.add(address);
            if (save) {
                this.addressBlacklist.save();
            }
            return true;

        } catch (IllegalStateException | IOException e) {
            LOGGER.info("Unable to add address to blacklist", e);
            return false;
        }
    }

    public boolean removeAddressFromBlacklist(String address, boolean save) {
        try {
            this.addressBlacklist.remove(address);

            if (save) {
                this.addressBlacklist.save();
            }
            return true;

        } catch (IllegalStateException | IOException e) {
            LOGGER.info("Unable to remove address from blacklist", e);
            return false;
        }
    }

    public boolean isAddressInBlacklist(String address) {
        if (this.addressBlacklist == null) {
            return false;
        }
        return this.addressBlacklist.contains(address);
    }

    public void saveBlacklist() {
        if (this.addressBlacklist == null) {
            return;
        }

        try {
            this.addressBlacklist.save();
        } catch (IOException e) {
            LOGGER.info("Unable to save blacklist - reverting back to last saved state");
            this.addressBlacklist.revert();
        }
    }

    public void revertBlacklist() {
        if (this.addressBlacklist == null) {
            return;
        }
        this.addressBlacklist.revert();
    }

    public String getBlacklistJSONString() {
        if (this.addressBlacklist == null) {
            return null;
        }
        return this.addressBlacklist.getJSONString();
    }

}
