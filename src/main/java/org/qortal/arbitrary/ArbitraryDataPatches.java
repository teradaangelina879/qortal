package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.qortal.repository.DataException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class ArbitraryDataPatches {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataPatches.class);

    private List<Path> paths;
    private Path finalPath;

    public ArbitraryDataPatches(List<Path> paths) {
        this.paths = paths;
    }

    public void applyPatches() throws DataException, IOException {
        try {
            this.preExecute();
            this.process();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() {
        if (this.paths == null || this.paths.isEmpty()) {
            throw new IllegalStateException(String.format("No paths available to build latest state"));
        }
    }

    private void postExecute() {

    }

    private void process() throws IOException {
        if (this.paths.size() == 1) {
            // No patching needed
            this.finalPath = this.paths.get(0);
            return;
        }

        Path pathBefore = this.paths.get(0);

        // Loop from the second path onwards
        for (int i=1; i<paths.size(); i++) {
            Path pathAfter = this.paths.get(i);
            ArbitraryDataCombiner combiner = new ArbitraryDataCombiner(pathBefore, pathAfter);
            combiner.combine();
            pathBefore = combiner.getFinalPath(); // TODO: cleanup
        }
        this.finalPath = pathBefore;
    }

    public Path getFinalPath() {
        return this.finalPath;
    }

}
