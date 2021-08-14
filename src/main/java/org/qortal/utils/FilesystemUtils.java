package org.qortal.utils;

import org.apache.commons.io.FileUtils;
import org.qortal.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

public class FilesystemUtils {

    public static boolean isDirectoryEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> directory = Files.newDirectoryStream(path)) {
                return !directory.iterator().hasNext();
            }
        }

        return false;
    }

    public static void copyAndReplaceDirectory(String sourceDirectoryLocation, String destinationDirectoryLocation) throws IOException {
        // Ensure parent folders exist in the destination
        File destFile = new File(destinationDirectoryLocation);
        if (destFile != null) {
            destFile.mkdirs();
        }
        if (destFile == null || !destFile.exists()) {
            throw new IOException("Destination directory doesn't exist");
        }

        Files.walk(Paths.get(sourceDirectoryLocation))
                .forEach(source -> {
                    Path destination = Paths.get(destinationDirectoryLocation, source.toString()
                            .substring(sourceDirectoryLocation.length()));
                    try {
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
    }


    /**
     * moveFile
     * Allows files to be moved between filesystems
     *
     * @param source
     * @param dest
     * @param cleanup
     * @throws IOException
     */
    public static void moveFile(Path source, Path dest, boolean cleanup) throws IOException {
        if (source.compareTo(dest) == 0) {
            // Source path matches destination path already
            return;
        }

        File sourceFile = new File(source.toString());
        if (sourceFile == null || !sourceFile.exists()) {
            throw new IOException("Source file doesn't exist");
        }
        if (!sourceFile.isFile()) {
            throw new IOException("Source isn't a file");
        }

        // Ensure parent folders exist in the destination
        File destFile = new File(dest.toString());
        File destParentFile = destFile.getParentFile();
        if (destParentFile != null) {
            destParentFile.mkdirs();
        }
        if (destParentFile == null || !destParentFile.exists()) {
            throw new IOException("Destination directory doesn't exist");
        }

        // Copy to destination
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);

        // Delete existing
        if (FilesystemUtils.pathInsideDataOrTempPath(source)) {
            System.out.println(String.format("Deleting file %s", source.toString()));
            Files.delete(source);
        }

        if (cleanup) {
            // ... and delete its parent directory if empty
            Path parentDirectory = source.getParent();
            if (FilesystemUtils.pathInsideDataOrTempPath(parentDirectory)) {
                Files.deleteIfExists(parentDirectory);
            }
        }
    }

    /**
     * moveDirectory
     * Allows directories to be moved between filesystems
     *
     * @param source
     * @param dest
     * @param cleanup
     * @throws IOException
     */
    public static void moveDirectory(Path source, Path dest, boolean cleanup) throws IOException {
        if (source.compareTo(dest) == 0) {
            // Source path matches destination path already
            return;
        }

        File sourceFile = new File(source.toString());
        File destFile = new File(dest.toString());
        if (sourceFile == null || !sourceFile.exists()) {
            throw new IOException("Source directory doesn't exist");
        }
        if (!sourceFile.isDirectory()) {
            throw new IOException("Source isn't a directory");
        }

        // Ensure parent folders exist in the destination
        destFile.mkdirs();
        if (destFile == null || !destFile.exists()) {
            throw new IOException("Destination directory doesn't exist");
        }

        // Copy to destination
        FilesystemUtils.copyAndReplaceDirectory(source.toString(), dest.toString());

        // Delete existing
        if (FilesystemUtils.pathInsideDataOrTempPath(source)) {
            File directory = new File(source.toString());
            System.out.println(String.format("Deleting directory %s", directory.toString()));
            FileUtils.deleteDirectory(directory);
        }

        if (cleanup) {
            // ... and delete its parent directory if empty
            Path parentDirectory = source.getParent();
            if (FilesystemUtils.pathInsideDataOrTempPath(parentDirectory)) {
                Files.deleteIfExists(parentDirectory);
            }
        }
    }

    public static boolean pathInsideDataOrTempPath(Path path) {
        if (path == null) {
            return false;
        }
        Path dataPath = Paths.get(Settings.getInstance().getDataPath()).toAbsolutePath();
        Path tempDataPath = Paths.get(Settings.getInstance().getTempDataPath()).toAbsolutePath();
        Path absolutePath = path.toAbsolutePath();
        if (absolutePath.startsWith(dataPath) || absolutePath.startsWith(tempDataPath)) {
            return true;
        }
        return false;
    }

}
