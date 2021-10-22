package org.qortal.arbitrary;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.metadata.ArbitraryDataMetadataPatch;
import org.qortal.settings.Settings;
import org.qortal.utils.FilesystemUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ArbitraryDataMerge {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataMerge.class);

    private Path pathBefore;
    private Path pathAfter;
    private String patchType;
    private Path mergePath;
    private String identifier;
    private ArbitraryDataMetadataPatch metadata;

    public ArbitraryDataMerge(Path pathBefore, Path pathAfter, String patchType) {
        this.pathBefore = pathBefore;
        this.pathAfter = pathAfter;
        this.patchType = patchType;
    }

    public void compute() throws IOException {
        try {
            this.preExecute();
            this.copyPreviousStateToMergePath();
            this.loadMetadata();
            this.applyDifferences();
            this.copyMetadata();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() {
        this.createRandomIdentifier();
        this.createOutputDirectory();
    }

    private void postExecute() {

    }

    private void createRandomIdentifier() {
        this.identifier = UUID.randomUUID().toString();
    }

    private void createOutputDirectory() {
        // Use the user-specified temp dir, as it is deterministic, and is more likely to be located on reusable storage hardware
        String baseDir = Settings.getInstance().getTempDataPath();
        Path tempDir = Paths.get(baseDir, "merge", this.identifier);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory");
        }
        this.mergePath = tempDir;
    }

    private void copyPreviousStateToMergePath() throws IOException {
        ArbitraryDataMerge.copyDirPathToBaseDir(this.pathBefore, this.mergePath, Paths.get(""));
    }

    private void loadMetadata() throws IOException {
        this.metadata = new ArbitraryDataMetadataPatch(this.pathAfter);
        this.metadata.read();
    }

    private void applyDifferences() throws IOException {

        List<Path> addedPaths = this.metadata.getAddedPaths();
        for (Path path : addedPaths) {
            LOGGER.info("File was added: {}", path.toString());
            Path filePath = Paths.get(this.pathAfter.toString(), path.toString());
            ArbitraryDataMerge.copyPathToBaseDir(filePath, this.mergePath, path);
        }

        List<Path> modifiedPaths = this.metadata.getModifiedPaths();
        for (Path path : modifiedPaths) {
            LOGGER.info("File was modified: {}", path.toString());
            this.applyPatch(path);
        }

        List<Path> removedPaths = this.metadata.getRemovedPaths();
        for (Path path : removedPaths) {
            LOGGER.info("File was removed: {}", path.toString());
            ArbitraryDataMerge.deletePathInBaseDir(this.mergePath, path);
        }
    }

    private void applyPatch(Path path) throws IOException {
        if (Objects.equals(this.patchType, "unified-diff")) {
            // Create destination file from patch
            this.applyUnifiedDiffPatch(path);
        }
        else {
            // Copy complete file
            Path filePath = Paths.get(this.pathAfter.toString(), path.toString());
            ArbitraryDataMerge.copyPathToBaseDir(filePath, this.mergePath, path);
        }
    }

    private void applyUnifiedDiffPatch(Path path) throws IOException {
        Path originalPath = Paths.get(this.pathBefore.toString(), path.toString());
        Path patchPath = Paths.get(this.pathAfter.toString(), path.toString());
        Path mergePath = Paths.get(this.mergePath.toString(), path.toString());

        if (!patchPath.toFile().exists()) {
            throw new IllegalStateException("Patch file doesn't exist, but its path was included in modifiedPaths");
        }

        // Delete an existing file, as we are starting from a duplicate of pathBefore
        File destFile = mergePath.toFile();
        if (destFile.exists() && destFile.isFile()) {
            Files.delete(mergePath);
        }

        List<String> originalContents = FileUtils.readLines(originalPath.toFile(), StandardCharsets.UTF_8);
        List<String> patchContents = FileUtils.readLines(patchPath.toFile(), StandardCharsets.UTF_8);

        // At first, parse the unified diff file and get the patch
        Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(patchContents);

        // Then apply the computed patch to the given text
        try {
            List<String> patchedContents = DiffUtils.patch(originalContents, patch);

            // Write the patched file to the merge directory
            FileWriter fileWriter = new FileWriter(mergePath.toString(), true);
            BufferedWriter writer = new BufferedWriter(fileWriter);
            for (String line : patchedContents) {
                writer.append(line);
                writer.newLine();
            }
            writer.flush();
            writer.close();

        } catch (PatchFailedException e) {
            throw new IllegalStateException(String.format("Failed to apply patch for path %s: %s", path, e.getMessage()));
        }
    }

    private void copyMetadata() throws IOException {
        Path filePath = Paths.get(this.pathAfter.toString(), ".qortal");
        ArbitraryDataMerge.copyPathToBaseDir(filePath, this.mergePath, Paths.get(".qortal"));
    }


    private static void copyPathToBaseDir(Path source, Path base, Path relativePath) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException(String.format("File not found: %s", source.toString()));
        }

        File sourceFile = source.toFile();
        Path dest = Paths.get(base.toString(), relativePath.toString());
        LOGGER.trace("Copying {} to {}", source, dest);

        if (sourceFile.isFile()) {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        else if (sourceFile.isDirectory()) {
            FilesystemUtils.copyAndReplaceDirectory(source.toString(), dest.toString());
        }
        else {
            throw new IOException(String.format("Invalid file: %s", source.toString()));
        }
    }

    private static void copyDirPathToBaseDir(Path source, Path base, Path relativePath) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException(String.format("File not found: %s", source.toString()));
        }

        Path dest = Paths.get(base.toString(), relativePath.toString());
        LOGGER.trace("Copying {} to {}", source, dest);
        FilesystemUtils.copyAndReplaceDirectory(source.toString(), dest.toString());
    }

    private static void deletePathInBaseDir(Path base, Path relativePath) throws IOException {
        Path dest = Paths.get(base.toString(), relativePath.toString());
        File file = new File(dest.toString());
        if (file.exists() && file.isFile()) {
            if (FilesystemUtils.pathInsideDataOrTempPath(dest)) {
                LOGGER.trace("Deleting file {}", dest);
                Files.delete(dest);
            }
        }
        if (file.exists() && file.isDirectory()) {
            if (FilesystemUtils.pathInsideDataOrTempPath(dest)) {
                LOGGER.trace("Deleting directory {}", dest);
                FileUtils.deleteDirectory(file);
            }
        }
    }

    public Path getMergePath() {
        return this.mergePath;
    }

}
