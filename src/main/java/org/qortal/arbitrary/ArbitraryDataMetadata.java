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

public class ArbitraryDataMetadata {

    protected static final Logger LOGGER = LogManager.getLogger(ArbitraryDataMetadata.class);

    protected Path filePath;
    protected Path qortalDirectoryPath;

    protected String jsonString;

    public ArbitraryDataMetadata(Path filePath) {
        this.filePath = filePath;
        this.qortalDirectoryPath = Paths.get(filePath.toString(), ".qortal");
    }

    protected String fileName() {
        // To be overridden
        return null;
    }

    protected void readJson() {
        // To be overridden
    }

    protected void buildJson() {
        // To be overridden
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


    protected void loadJson() throws IOException {
        Path path = Paths.get(this.qortalDirectoryPath.toString(), this.fileName());
        File patchFile = new File(path.toString());
        if (!patchFile.exists()) {
            throw new IOException(String.format("Patch file doesn't exist: %s", path.toString()));
        }

        this.jsonString = new String(Files.readAllBytes(path));
    }

    protected void createQortalDirectory() {
        try {
            Files.createDirectories(this.qortalDirectoryPath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create .qortal directory");
        }
    }

    protected void writeToQortalPath() throws IOException {
        Path patchPath = Paths.get(this.qortalDirectoryPath.toString(), this.fileName());
        BufferedWriter writer = new BufferedWriter(new FileWriter(patchPath.toString()));
        writer.write(this.jsonString);
        writer.close();
    }


    public String getJsonString() {
        return this.jsonString;
    }

}
