package org.qortal.utils;

import org.qortal.list.ResourceListManager;

import java.util.List;

public class ListUtils {

    /* Blocking */

    public static List<String> blockedNames() {
        return ResourceListManager.getInstance().getStringsInList("blockedNames");
    }

    public static boolean isNameBlocked(String name) {
        return ResourceListManager.getInstance().listContains("blockedNames", name, false);
    }

    public static boolean isAddressBlocked(String address) {
        return ResourceListManager.getInstance().listContains("blockedAddresses", address, true);
    }


    /* Following */

    public static List<String> followedNames() {
        return ResourceListManager.getInstance().getStringsInList("followedNames");
    }

    public static boolean isFollowingName(String name) {
        return ResourceListManager.getInstance().listContains("followedNames", name, false);
    }

    public static int followedNamesCount() {
        return ListUtils.followedNames().size();
    }

}
