package org.qortal.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;


public class DataFile {

    // Validation results
    public enum ValidationResult {
        OK(1),
        FILE_TOO_LARGE(10),
        FILE_NOT_FOUND(11);

        public final int value;

        private static final Map<Integer, DataFile.ValidationResult> map = stream(DataFile.ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

        ValidationResult(int value) {
            this.value = value;
        }

        public static DataFile.ValidationResult valueOf(int value) {
            return map.get(value);
        }
    }

    private static final Logger LOGGER = LogManager.getLogger(DataFile.class);

    public static final long MAX_FILE_SIZE = 1 * 1024 * 1024 * 1024; // 1GiB
    public static final int CHUNK_SIZE = 1 * 1024 * 1024; // 1MiB
    public static int SHORT_DIGEST_LENGTH = 8;

    protected String filePath;
    private ArrayList<DataFileChunk> chunks;

    public DataFile() {
    }

    public DataFile(String filePath) {
        this.createDataDirectory();
        this.filePath = filePath;
        this.chunks = new ArrayList<>();

        if (!this.isInBaseDirectory(filePath)) {
            // Copy file to base directory
            LOGGER.debug("Copying file to data directory...");
            this.filePath = this.copyToDataDirectory();
            if (this.filePath == null) {
                throw new IllegalStateException("Invalid file path after copy");
            }
        }
    }

    public DataFile(File file) {
        this(file.getPath());
    }

    public DataFile(byte[] fileContent) {
        if (fileContent == null) {
            LOGGER.error("fileContent is null");
            return;
        }

        String base58Digest = Base58.encode(Crypto.digest(fileContent));
        LOGGER.debug(String.format("File digest: %s, size: %d bytes", base58Digest, fileContent.length));

        String outputFilePath = this.getOutputFilePath(base58Digest, true);
        File outputFile = new File(outputFilePath);
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(fileContent);
            this.filePath = outputFilePath;
            // Verify hash
            if (!base58Digest.equals(this.base58Digest())) {
                LOGGER.error("Digest {} does not match file digest {}", base58Digest, this.base58Digest());
                this.delete();
                throw new IllegalStateException("Data file digest validation failed");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write data to file");
        }
    }

    public static DataFile fromBase58Digest(String base58Digest) {
        String filePath = DataFile.getOutputFilePath(base58Digest, false);
        return new DataFile(filePath);
    }

    public static DataFile fromDigest(byte[] digest) {
        return DataFile.fromBase58Digest(Base58.encode(digest));
    }

    private boolean createDataDirectory() {
        // Create the data directory if it doesn't exist
        String dataPath = Settings.getInstance().getDataPath();
        Path dataDirectory = Paths.get(dataPath);
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            LOGGER.error("Unable to create data directory");
            return false;
        }
        return true;
    }

    private String copyToDataDirectory() {
        String outputFilePath = this.getOutputFilePath(this.base58Digest(), true);
        Path source = Paths.get(this.filePath).toAbsolutePath();
        Path dest = Paths.get(outputFilePath).toAbsolutePath();
        try {
            return Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING).toString();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to copy file to data directory");
        }
    }

    public static String getOutputFilePath(String base58Digest, boolean createDirectories) {
        String base58DigestFirst2Chars = base58Digest.substring(0, Math.min(base58Digest.length(), 2));
        String base58DigestNext2Chars = base58Digest.substring(2, Math.min(base58Digest.length(), 4));
        String outputDirectory =  Settings.getInstance().getDataPath() + File.separator + base58DigestFirst2Chars + File.separator + base58DigestNext2Chars;
        Path outputDirectoryPath = Paths.get(outputDirectory);

        if (createDirectories) {
            try {
                Files.createDirectories(outputDirectoryPath);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create data subdirectory");
            }
        }
        return outputDirectory + base58Digest;
    }

    public ValidationResult isValid() {
        try {
            // Ensure the file exists on disk
            Path path = Paths.get(this.filePath);
            if (!Files.exists(path)) {
                LOGGER.error("File doesn't exist at path {}", this.filePath);
                return ValidationResult.FILE_NOT_FOUND;
            }

            // Validate the file size
            long fileSize = Files.size(path);
            if (fileSize > MAX_FILE_SIZE) {
                LOGGER.error(String.format("DataFile is too large: %d bytes (max size: %d bytes)", fileSize, MAX_FILE_SIZE));
                return DataFile.ValidationResult.FILE_TOO_LARGE;
            }

        } catch (IOException e) {
            return ValidationResult.FILE_NOT_FOUND;
        }

        return ValidationResult.OK;
    }

    public void addChunk(DataFileChunk chunk) {
        this.chunks.add(chunk);
    }

    public int split(int chunkSize) {
        try {

            File file = this.getFile();
            byte[] buffer = new byte[chunkSize];
            this.chunks = new ArrayList<>();

            if (file != null) {
                try (FileInputStream fileInputStream = new FileInputStream(file);
                     BufferedInputStream bis = new BufferedInputStream(fileInputStream)) {

                    int numberOfBytes;
                    while ((numberOfBytes = bis.read(buffer)) > 0) {
                        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                            out.write(buffer, 0, numberOfBytes);
                            out.flush();

                            DataFileChunk chunk = new DataFileChunk(out.toByteArray());
                            ValidationResult validationResult = chunk.isValid();
                            if (validationResult == ValidationResult.OK) {
                                this.chunks.add(chunk);
                            } else {
                                throw new IllegalStateException(String.format("Chunk %s is invalid", chunk));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to split file into chunks");
        }

        return this.chunks.size();
    }

    public boolean join() {
        // Ensure we have chunks
        if (this.chunks != null && this.chunks.size() > 0) {

            // Create temporary path for joined file
            Path tempPath;
            try {
                tempPath = Files.createTempFile(this.chunks.get(0).base58Digest(), ".tmp");
            } catch (IOException e) {
                return false;
            }
            this.filePath = tempPath.toString();

            // Join the chunks
            File outputFile = new File(this.filePath);
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                for (DataFileChunk chunk : this.chunks) {
                    File sourceFile = new File(chunk.filePath);
                    BufferedInputStream in = new BufferedInputStream(new FileInputStream(sourceFile));
                    byte[] buffer = new byte[2048];
                    int inSize = -1;
                    while ((inSize = in.read(buffer)) != -1) {
                        out.write(buffer, 0, inSize);
                    }
                    in.close();
                }
                out.close();

                // Copy temporary file to data directory
                this.filePath = this.copyToDataDirectory();
                Files.delete(tempPath);

                return true;
            } catch (FileNotFoundException e) {
                return false;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    public boolean delete() {
        // Delete the complete file
        Path path = Paths.get(this.filePath);
        if (Files.exists(path)) {
            try {
                Files.delete(path);
                this.cleanupFilesystem();
                LOGGER.debug("Deleted file {}", path.toString());
                return true;
            } catch (IOException e) {
                LOGGER.warn("Couldn't delete DataFileChunk at path {}", this.filePath);
            }
        }
        return false;
    }

    public boolean deleteAll() {
        // Delete the complete file
        boolean success = this.delete();

        // Delete the individual chunks
        if (this.chunks != null && this.chunks.size() > 0) {
            Iterator iterator = this.chunks.iterator();
            while (iterator.hasNext()) {
                DataFileChunk chunk = (DataFileChunk) iterator.next();
                chunk.delete();
                iterator.remove();
                success = true;
            }
        }
        return success;
    }

    protected void cleanupFilesystem() {
        String path = this.filePath;

        // Iterate through two levels of parent directories, and delete if empty
        for (int i=0; i<2; i++) {
            Path directory = Paths.get(path).getParent().toAbsolutePath();
            try (Stream<Path> files = Files.list(directory)) {
                final long count = files.count();
                if (count == 0) {
                    Files.delete(directory);
                }
            } catch (IOException e) {
                LOGGER.warn("Unable to count files in directory", e);
            }
            path = directory.toString();
        }
    }

    public byte[] getBytes() {
        Path path = Paths.get(this.filePath);
        try {
            byte[] bytes = Files.readAllBytes(path);
            return bytes;
        } catch (IOException e) {
            LOGGER.error("Unable to read bytes for file");
            return null;
        }
    }


    /* Helper methods */

    private boolean isInBaseDirectory(String filePath) {
        Path path = Paths.get(filePath).toAbsolutePath();
        String dataPath = Settings.getInstance().getDataPath();
        String basePath = Paths.get(dataPath).toAbsolutePath().toString();
        if (path.startsWith(basePath)) {
            return true;
        }
        return false;
    }

    public boolean exists() {
        File file = new File(this.filePath);
        return file.exists();
    }

    public long size() {
        Path path = Paths.get(this.filePath);
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    public int chunkCount() {
        return this.chunks.size();
    }

    private File getFile() {
        File file = new File(this.filePath);
        if (file.exists()) {
            return file;
        }
        return null;
    }

    public String getFilePath() {
        return this.filePath;
    }

    public byte[] digest() {
        File file = this.getFile();
        if (file != null && file.exists()) {
            try {
                byte[] fileContent = Files.readAllBytes(file.toPath());
                return Crypto.digest(fileContent);

            } catch (IOException e) {
                LOGGER.error("Couldn't compute digest for DataFile");
            }
        }
        return null;
    }

    public byte[] chunkHashes() {
        if (this.chunks != null && this.chunks.size() > 0) {
            // Return null if we only have one chunk, with the same hash as the parent
            if (this.digest().equals(this.chunks.get(0).digest())) {
                return null;
            }

            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                for (DataFileChunk chunk : this.chunks) {
                    byte[] chunkHash = chunk.digest();
                    if (chunkHash.length != 32) {
                        LOGGER.info("Invalid chunk hash length: {}", chunkHash.length);
                        throw new IllegalStateException("Invalid chunk hash length");
                    }
                    outputStream.write(chunk.digest());
                }
                return outputStream.toByteArray();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    public String base58Digest() {
        if (this.digest() != null) {
            return Base58.encode(this.digest());
        }
        return null;
    }

    public String shortDigest() {
        if (this.base58Digest() == null) {
            return null;
        }
        return this.base58Digest().substring(0, Math.min(this.base58Digest().length(), SHORT_DIGEST_LENGTH));
    }

    public String printChunks() {
        String outputString = "";
        if (this.chunkCount() > 0) {
            for (DataFileChunk chunk : this.chunks) {
                if (outputString.length() > 0) {
                    outputString = outputString.concat(",");
                }
                outputString = outputString.concat(chunk.base58Digest());
            }
        }
        return outputString;
    }

    @Override
    public String toString() {
        return this.shortDigest();
    }
}
