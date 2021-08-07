package org.qortal.list;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.util.IO;

import java.io.IOException;

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

    public boolean addAddressToBlacklist(String address) {
        try {
            this.addressBlacklist.add(address);
            this.addressBlacklist.save();
            return true;

        } catch (IllegalStateException | IOException e) {
            LOGGER.info("Unable to add address to blacklist", e);
            return false;
        }
    }

    public boolean removeAddressFromBlacklist(String address) {
        try {
            this.addressBlacklist.remove(address);
            this.addressBlacklist.save();
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

}
