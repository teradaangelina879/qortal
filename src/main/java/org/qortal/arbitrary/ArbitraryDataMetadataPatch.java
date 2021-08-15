package org.qortal.arbitrary;

import org.json.JSONArray;
import org.json.JSONObject;
import org.qortal.utils.Base58;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ArbitraryDataMetadataPatch extends ArbitraryDataMetadata {

    private List<Path> addedPaths;
    private List<Path> modifiedPaths;
    private List<Path> removedPaths;
    private byte[] previousSignature;

    public ArbitraryDataMetadataPatch(Path filePath) {
        super(filePath);

        this.addedPaths = new ArrayList<>();
        this.modifiedPaths = new ArrayList<>();
        this.removedPaths = new ArrayList<>();
    }

    @Override
    protected String fileName() {
        return "patch";
    }

    @Override
    protected void readJson() {
        if (this.jsonString == null) {
            throw new IllegalStateException("Patch JSON string is null");
        }

        JSONObject patch = new JSONObject(this.jsonString);
        if (patch.has("prevSig")) {
            String prevSig = patch.getString("prevSig");
            if (prevSig != null) {
                this.previousSignature = Base58.decode(prevSig);
            }
        }
        if (patch.has("added")) {
            JSONArray added = (JSONArray) patch.get("added");
            if (added != null) {
                for (int i=0; i<added.length(); i++) {
                    String pathString = added.getString(i);
                    this.addedPaths.add(Paths.get(pathString));
                }
            }
        }
        if (patch.has("modified")) {
            JSONArray modified = (JSONArray) patch.get("modified");
            if (modified != null) {
                for (int i=0; i<modified.length(); i++) {
                    String pathString = modified.getString(i);
                    this.modifiedPaths.add(Paths.get(pathString));
                }
            }
        }
        if (patch.has("removed")) {
            JSONArray removed = (JSONArray) patch.get("removed");
            if (removed != null) {
                for (int i=0; i<removed.length(); i++) {
                    String pathString = removed.getString(i);
                    this.removedPaths.add(Paths.get(pathString));
                }
            }
        }
    }

    @Override
    protected void buildJson() {
        JSONObject patch = new JSONObject();
        patch.put("prevSig", Base58.encode(this.previousSignature));
        patch.put("added", new JSONArray(this.addedPaths));
        patch.put("modified", new JSONArray(this.modifiedPaths));
        patch.put("removed", new JSONArray(this.removedPaths));

        this.jsonString = patch.toString(2);
        LOGGER.info("Patch metadata: {}", this.jsonString);
    }


    public void setAddedPaths(List<Path> addedPaths) {
        this.addedPaths = addedPaths;
    }

    public List<Path> getAddedPaths() {
        return this.addedPaths;
    }

    public void setModifiedPaths(List<Path> modifiedPaths) {
        this.modifiedPaths = modifiedPaths;
    }

    public List<Path> getModifiedPaths() {
        return this.modifiedPaths;
    }

    public void setRemovedPaths(List<Path> removedPaths) {
        this.removedPaths = removedPaths;
    }

    public List<Path> getRemovedPaths() {
        return this.removedPaths;
    }

    public void setPreviousSignature(byte[] previousSignature) {
        this.previousSignature = previousSignature;
    }

    public byte[] getPreviousSignature() {
        return this.previousSignature;
    }

}
