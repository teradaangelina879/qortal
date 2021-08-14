package org.qortal.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DataFileCombiner {

    private static final Logger LOGGER = LogManager.getLogger(DataFileCombiner.class);

    private Path pathBefore;
    private Path pathAfter;
    private Path finalPath;

    public DataFileCombiner(Path pathBefore, Path pathAfter) {
        this.pathBefore = pathBefore;
        this.pathAfter = pathAfter;
    }

    public void combine() throws IOException {
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

    private void process() throws IOException {
        DataFileMerge merge = new DataFileMerge(this.pathBefore, this.pathAfter);
        merge.compute();
        this.finalPath = merge.getMergePath();
    }

    public Path getFinalPath() {
        return this.finalPath;
    }

}
