package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.qortal.utils.Base58;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public ArbitraryDataMetadata(List<Path> addedPaths, List<Path> modifiedPaths, List<Path> removedPaths,
                                 Path filePath, byte[] previousSignature) {
        this.addedPaths = addedPaths;
        this.modifiedPaths = modifiedPaths;
        this.removedPaths = removedPaths;
        this.filePath = filePath;
        this.previousSignature = previousSignature;
    }

    public void write() throws IOException {
        this.buildJson();
        this.createQortalDirectory();
        this.writeToQortalPath();
    }

    private void buildJson() {
        JSONArray addedPathsJson = new JSONArray(this.addedPaths);
        JSONArray modifiedPathsJson = new JSONArray(this.modifiedPaths);
        JSONArray removedPathsJson = new JSONArray(this.removedPaths);
        String previousSignature58 = Base58.encode(this.previousSignature);

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("prevSig", previousSignature58);
        jsonObject.put("added", addedPathsJson);
        jsonObject.put("modified", modifiedPathsJson);
        jsonObject.put("removed", removedPathsJson);

        this.jsonString = jsonObject.toString(4);
    }

    private void createQortalDirectory() {
        Path qortalDir = Paths.get(this.filePath.toString(), ".qortal");
        try {
            Files.createDirectories(qortalDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create .qortal directory");
        }
        this.qortalDirectoryPath = qortalDir;
    }

    private void writeToQortalPath() throws IOException {
        Path statePath = Paths.get(this.qortalDirectoryPath.toString(), "patch");
        BufferedWriter writer = new BufferedWriter(new FileWriter(statePath.toString()));
        writer.write(this.jsonString);
        writer.close();
    }


    public String getJsonString() {
        return this.jsonString;
    }

}
