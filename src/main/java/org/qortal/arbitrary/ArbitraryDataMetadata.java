package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.qortal.utils.Base58;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ArbitraryDataMetadata {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataMetadata.class);

    private List<Path> addedPaths;
    private List<Path> modifiedPaths;
    private List<Path> removedPaths;
    private Path filePath;
    private Path qortalDirectoryPath;
    private byte[] previousSignature;

    private String jsonString;

    public ArbitraryDataMetadata(Path filePath) {
        this.filePath = filePath;
        this.qortalDirectoryPath = Paths.get(filePath.toString(), ".qortal");

        this.addedPaths = new ArrayList<>();
        this.modifiedPaths = new ArrayList<>();
        this.removedPaths = new ArrayList<>();
    }

    public void read() throws IOException {
        this.loadJson();
        this.readJson();
    }

    public void write() throws IOException {
        this.buildJson();
        this.createQortalDirectory();
        this.writeToQortalPath();
    }


    private void loadJson() throws IOException {
        Path path = Paths.get(this.qortalDirectoryPath.toString(), "patch");
        File patchFile = new File(path.toString());
        if (!patchFile.exists()) {
            throw new IOException(String.format("Patch file doesn't exist: %s", path.toString()));
        }

        this.jsonString = new String(Files.readAllBytes(path));
    }

    private void readJson() {
        if (this.jsonString == null) {
            throw new IllegalStateException("Patch JSON string is null");
        }

        JSONObject patch = new JSONObject(this.jsonString);
        if (patch.has("prevSig")) {
            String prevSig = (String)patch.get("prevSig");
            if (prevSig != null) {
                this.previousSignature = Base58.decode(prevSig);
            }
        }
        if (patch.has("added")) {
            JSONArray added = (JSONArray) patch.get("added");
            if (added != null) {
                for (int i=0; i<added.length(); i++) {
                    String pathString = (String)added.get(i);
                    this.addedPaths.add(Paths.get(pathString));
                }
            }
        }
        if (patch.has("modified")) {
            JSONArray modified = (JSONArray) patch.get("modified");
            if (modified != null) {
                for (int i=0; i<modified.length(); i++) {
                    String pathString = (String)modified.get(i);
                    this.modifiedPaths.add(Paths.get(pathString));
                }
            }
        }
        if (patch.has("removed")) {
            JSONArray removed = (JSONArray) patch.get("removed");
            if (removed != null) {
                for (int i=0; i<removed.length(); i++) {
                    String pathString = (String)removed.get(i);
                    this.removedPaths.add(Paths.get(pathString));
                }
            }
        }
    }



    private void buildJson() {
        JSONArray addedPathsJson = new JSONArray(this.addedPaths);
        JSONArray modifiedPathsJson = new JSONArray(this.modifiedPaths);
        JSONArray removedPathsJson = new JSONArray(this.removedPaths);
        String previousSignature58 = Base58.encode(this.previousSignature);

        JSONObject patch = new JSONObject();
        patch.put("prevSig", previousSignature58);
        patch.put("added", addedPathsJson);
        patch.put("modified", modifiedPathsJson);
        patch.put("removed", removedPathsJson);

        this.jsonString = patch.toString(2);
    }

    private void createQortalDirectory() {
        try {
            Files.createDirectories(this.qortalDirectoryPath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create .qortal directory");
        }
    }

    private void writeToQortalPath() throws IOException {
        Path patchPath = Paths.get(this.qortalDirectoryPath.toString(), "patch");
        BufferedWriter writer = new BufferedWriter(new FileWriter(patchPath.toString()));
        writer.write(this.jsonString);
        writer.close();
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
        return this.getPreviousSignature();
    }

    public String getJsonString() {
        return this.jsonString;
    }

}
