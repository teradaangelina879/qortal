package org.qortal.arbitrary;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.crypto.Crypto;
import org.qortal.utils.FilesystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

public class ArbitraryDataMerge {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataMerge.class);

    private Path pathBefore;
    private Path pathAfter;
    private Path mergePath;

    public ArbitraryDataMerge(Path pathBefore, Path pathAfter) {
        this.pathBefore = pathBefore;
        this.pathAfter = pathAfter;
    }

    public void compute() throws IOException {
        try {
            this.preExecute();
            this.copyPreviousStateToMergePath();
            this.findDifferences();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() {
        this.createOutputDirectory();
    }

    private void postExecute() {

    }

    private void createOutputDirectory() {
        // Ensure temp folder exists
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("qortal-diff");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory");
        }
        this.mergePath = tempDir;
    }

    private void copyPreviousStateToMergePath() throws IOException {
        ArbitraryDataMerge.copyDirPathToBaseDir(this.pathBefore, this.mergePath, Paths.get(""));
    }

    private void findDifferences() {
        final Path pathBeforeAbsolute = this.pathBefore.toAbsolutePath();
        final Path pathAfterAbsolute = this.pathAfter.toAbsolutePath();
        final Path mergePathAbsolute = this.mergePath.toAbsolutePath();

//        LOGGER.info("this.pathBefore: {}", this.pathBefore);
//        LOGGER.info("this.pathAfter: {}", this.pathAfter);
//        LOGGER.info("pathBeforeAbsolute: {}", pathBeforeAbsolute);
//        LOGGER.info("pathAfterAbsolute: {}", pathAfterAbsolute);
//        LOGGER.info("mergePathAbsolute: {}", mergePathAbsolute);


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
                    boolean wasRemoved = false;

                    if (after.toString().endsWith(".removed")) {
                        LOGGER.trace("File was removed: {}", after.toString());
                        wasRemoved = true;
                    }
                    else if (!Files.exists(filePathBefore)) {
                        LOGGER.trace("File was added: {}", after.toString());
                        wasAdded = true;
                    }
                    else if (Files.size(after) != Files.size(filePathBefore)) {
                        // Check file size first because it's quicker
                        LOGGER.trace("File size was modified: {}", after.toString());
                        wasModified = true;
                    }
                    else if (!Arrays.equals(ArbitraryDataMerge.digestFromPath(after), ArbitraryDataMerge.digestFromPath(filePathBefore))) {
                        // Check hashes as a last resort
                        LOGGER.trace("File contents were modified: {}", after.toString());
                        wasModified = true;
                    }

                    if (wasAdded | wasModified) {
                        ArbitraryDataMerge.copyFilePathToBaseDir(after, mergePathAbsolute, filePathAfter);
                    }

                    if (wasRemoved) {
                        if (filePathAfter.toString().endsWith(".removed")) {
                            // Trim the ".removed"
                            Path filePathAfterTrimmed = Paths.get(filePathAfter.toString().substring(0, filePathAfter.toString().length()-8));
                            ArbitraryDataMerge.deletePathInBaseDir(mergePathAbsolute, filePathAfterTrimmed);
                        }
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

        Path dest = Paths.get(base.toString(), relativePath.toString());
        LOGGER.trace("Copying {} to {}", source, dest);
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyDirPathToBaseDir(Path source, Path base, Path relativePath) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException(String.format("File not found: %s", source.toString()));
        }

        Path dest = Paths.get(base.toString(), relativePath.toString());
        LOGGER.trace("Copying {} to {}", source, dest);
        FilesystemUtils.copyDirectory(source.toString(), dest.toString());
    }

    private static void deletePathInBaseDir(Path base, Path relativePath) throws IOException {
        Path dest = Paths.get(base.toString(), relativePath.toString());
        File file = new File(dest.toString());
        if (file.exists() && file.isFile()) {
            LOGGER.trace("Deleting file {}", dest);
            Files.delete(dest);
        }
        if (file.exists() && file.isDirectory()) {
            LOGGER.trace("Deleting directory {}", dest);
            FileUtils.deleteDirectory(file);
        }
    }

    public Path getMergePath() {
        return this.mergePath;
    }

}
