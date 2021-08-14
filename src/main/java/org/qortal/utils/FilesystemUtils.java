package org.qortal.utils;

import org.qortal.settings.Settings;

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
