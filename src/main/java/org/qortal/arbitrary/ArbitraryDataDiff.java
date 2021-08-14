package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.UUID;

public class ArbitraryDataDiff {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataDiff.class);

    private Path pathBefore;
    private Path pathAfter;
    private Path diffPath;
    private String identifier;

    public ArbitraryDataDiff(Path pathBefore, Path pathAfter) {
        this.pathBefore = pathBefore;
        this.pathAfter = pathAfter;
    }

    public void compute() {
        try {
            this.preExecute();
            this.findAddedOrModifiedFiles();
            this.findRemovedFiles();

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
        Path tempDir = Paths.get(baseDir, "diff", this.identifier);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory");
        }
        this.diffPath = tempDir;
    }

    private void findAddedOrModifiedFiles() {
        final Path pathBeforeAbsolute = this.pathBefore.toAbsolutePath();
        final Path pathAfterAbsolute = this.pathAfter.toAbsolutePath();
        final Path diffPathAbsolute = this.diffPath.toAbsolutePath();

//        LOGGER.info("this.pathBefore: {}", this.pathBefore);
//        LOGGER.info("this.pathAfter: {}", this.pathAfter);
//        LOGGER.info("pathBeforeAbsolute: {}", pathBeforeAbsolute);
//        LOGGER.info("pathAfterAbsolute: {}", pathAfterAbsolute);
//        LOGGER.info("diffPathAbsolute: {}", diffPathAbsolute);


        try {
            // Check for additions or modifications
            Files.walkFileTree(this.pathAfter, new FileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path after, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path after, BasicFileAttributes attrs) throws IOException {
                    Path filePathAfter = pathAfterAbsolute.relativize(after.toAbsolutePath());
                    Path filePathBefore = pathBeforeAbsolute.resolve(filePathAfter);

                    boolean wasAdded = false;
                    boolean wasModified = false;

                    if (!Files.exists(filePathBefore)) {
                        LOGGER.info("File was added: {}", after.toString());
                        wasAdded = true;
                    }
                    else if (Files.size(after) != Files.size(filePathBefore)) {
                        // Check file size first because it's quicker
                        LOGGER.info("File size was modified: {}", after.toString());
                        wasModified = true;
                    }
                    else if (!Arrays.equals(ArbitraryDataDiff.digestFromPath(after), ArbitraryDataDiff.digestFromPath(filePathBefore))) {
                        // Check hashes as a last resort
                        LOGGER.info("File contents were modified: {}", after.toString());
                        wasModified = true;
                    }

                    if (wasAdded | wasModified) {
                        ArbitraryDataDiff.copyFilePathToBaseDir(after, diffPathAbsolute, filePathAfter);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e){
                    LOGGER.info("File visit failed: {}, error: {}", file.toString(), e.getMessage());
                    // TODO: throw exception?
                    return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

            });
        } catch (IOException e) {
            // TODO: throw exception?
            LOGGER.info("IOException when walking through file tree: {}", e.getMessage());
        }
    }

    private void findRemovedFiles() {
        final Path pathBeforeAbsolute = this.pathBefore.toAbsolutePath();
        final Path pathAfterAbsolute = this.pathAfter.toAbsolutePath();
        final Path diffPathAbsolute = this.diffPath.toAbsolutePath();
        try {
            // Check for removals
            Files.walkFileTree(this.pathBefore, new FileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path before, BasicFileAttributes attrs) throws IOException {
                    Path directoryPathBefore = pathBeforeAbsolute.relativize(before.toAbsolutePath());
                    Path directoryPathAfter = pathAfterAbsolute.resolve(directoryPathBefore);

                    if (!Files.exists(directoryPathAfter)) {
                        LOGGER.info("Directory was removed: {}", directoryPathAfter.toString());

                        ArbitraryDataDiff.markFilePathAsRemoved(diffPathAbsolute, directoryPathBefore);
                        // TODO: we might need to mark directories differently to files
                        // TODO: add path to manifest JSON
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path before, BasicFileAttributes attrs) throws IOException {
                    Path filePathBefore = pathBeforeAbsolute.relativize(before.toAbsolutePath());
                    Path filePathAfter = pathAfterAbsolute.resolve(filePathBefore);

                    if (!Files.exists(filePathAfter)) {
                        LOGGER.trace("File was removed: {}", before.toString());

                        ArbitraryDataDiff.markFilePathAsRemoved(diffPathAbsolute, filePathBefore);
                        // TODO: add path to manifest JSON
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e){
                    LOGGER.info("File visit failed: {}, error: {}", file.toString(), e.getMessage());
                    // TODO: throw exception?
                    return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

            });
        } catch (IOException e) {
            LOGGER.info("IOException when walking through file tree: {}", e.getMessage());
        }
    }


    private static byte[] digestFromPath(Path path) {
        try {
            return Crypto.digest(Files.readAllBytes(path));
        } catch (IOException e) {
            return null;
        }
    }

    private static void copyFilePathToBaseDir(Path source, Path base, Path relativePath) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException(String.format("File not found: %s", source.toString()));
        }

        // Ensure parent folders exist in the destination
        Path dest = Paths.get(base.toString(), relativePath.toString());
        File file = new File(dest.toString());
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        LOGGER.trace("Copying {} to {}", source, dest);
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void markFilePathAsRemoved(Path base, Path relativePath) throws IOException {
        String newFilename = relativePath.toString().concat(".removed");
        Path dest = Paths.get(base.toString(), newFilename);
        File file = new File(dest.toString());
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        LOGGER.info("Creating file {}", dest);
        file.createNewFile();
    }


    public Path getDiffPath() {
        return this.diffPath;
    }

}
