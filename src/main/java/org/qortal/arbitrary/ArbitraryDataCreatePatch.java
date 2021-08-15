package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.repository.DataException;
import org.qortal.utils.FilesystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ArbitraryDataCreatePatch {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataCreatePatch.class);

    private Path pathBefore;
    private Path pathAfter;
    private byte[] previousSignature;
    private Path finalPath;

    public ArbitraryDataCreatePatch(Path pathBefore, Path pathAfter, byte[] previousSignature) {
        this.pathBefore = pathBefore;
        this.pathAfter = pathAfter;
        this.previousSignature = previousSignature;
    }

    public void create() throws DataException, IOException {
        try {
            this.preExecute();
            this.process();

        } catch (Exception e) {
            this.cleanupOnFailure();
            throw e;

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

    private void cleanupOnFailure() {
        try {
            FilesystemUtils.safeDeleteDirectory(this.finalPath, true);
        } catch (IOException e) {
            LOGGER.info("Unable to cleanup diff directory on failure");
        }
    }

    private void process() throws IOException {

        ArbitraryDataDiff diff = new ArbitraryDataDiff(this.pathBefore, this.pathAfter, this.previousSignature);
        this.finalPath = diff.getDiffPath();
        diff.compute();
    }

    public Path getFinalPath() {
        return this.finalPath;
    }

}
