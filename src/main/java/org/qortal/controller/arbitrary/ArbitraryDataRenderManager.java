package org.qortal.controller.arbitrary;

import org.qortal.arbitrary.ArbitraryDataResource;

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
        ArbitraryDataResource broadResource = new ArbitraryDataResource(resource.getResourceId(), null, null, null);

        for (ArbitraryDataResource authorizedResource : this.authorizedResources) {
            if (authorizedResource != null && resource != null) {
                // Check for exact match
                if (Objects.equals(authorizedResource.getUniqueKey(), resource.getUniqueKey())) {
                    return true;
                }
                // Check for a broad authorization (which applies to all services and identifiers under an authorized name)
                if (Objects.equals(authorizedResource.getUniqueKey(), broadResource.getUniqueKey())) {
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
