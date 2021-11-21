package org.qortal.controller.arbitrary;

import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataResource;
import org.qortal.arbitrary.misc.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ArbitraryDataRenderManager {

    private static ArbitraryDataRenderManager instance;

    /**
     * List to keep track of authorized resources for rendering.
     */
    private List<ArbitraryDataResource> authorizedResources = Collections.synchronizedList(new ArrayList<>());


    public ArbitraryDataRenderManager() {

    }

    public static ArbitraryDataRenderManager getInstance() {
        if (instance == null)
            instance = new ArbitraryDataRenderManager();

        return instance;
    }

    public boolean isAuthorized(ArbitraryDataResource resource) {
        for (ArbitraryDataResource authorizedResource : this.authorizedResources) {
            if (authorizedResource != null && resource != null) {
                if (Objects.equals(authorizedResource.toString(), resource.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void addToAuthorizedResources(ArbitraryDataResource resource) {
        if (!this.isAuthorized(resource)) {
            this.authorizedResources.add(resource);
        }
    }

}
