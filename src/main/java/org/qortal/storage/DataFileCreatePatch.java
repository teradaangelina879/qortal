package org.qortal.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.repository.DataException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DataFileCreatePatch {

    private static final Logger LOGGER = LogManager.getLogger(DataFileCreatePatch.class);

    private Path pathBefore;
    private Path pathAfter;
    private Path finalPath;

    public DataFileCreatePatch(Path pathBefore, Path pathAfter) {
        this.pathBefore = pathBefore;
        this.pathAfter = pathAfter;
    }

    public void create() throws DataException, IOException {
        try {
            this.preExecute();
            this.process();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() {
        if (this.pathBefore == null || this.pathAfter == null) {
            throw new IllegalStateException(String.format("No paths available to build patch"));
        }
        if (!Files.exists(this.pathBefore) || !Files.exists(this.pathAfter)) {
            throw new IllegalStateException(String.format("Unable to create patch because at least one path doesn't exist"));
        }
    }

    private void postExecute() {

    }

    private void process() {

        DataFileDiff diff = new DataFileDiff(this.pathBefore, this.pathAfter);
        diff.compute();
        this.finalPath = diff.getDiffPath();
    }

    public Path getFinalPath() {
        return this.finalPath;
    }

}
