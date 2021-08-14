package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.FilesystemUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;


public class ArbitraryDataFile {

    // Validation results
    public enum ValidationResult {
        OK(1),
        FILE_TOO_LARGE(10),
        FILE_NOT_FOUND(11);

        public final int value;

        private static final Map<Integer, ArbitraryDataFile.ValidationResult> map = stream(ArbitraryDataFile.ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

        ValidationResult(int value) {
            this.value = value;
        }

        public static ArbitraryDataFile.ValidationResult valueOf(int value) {
            return map.get(value);
        }
    }

    // Resource ID types
    public enum ResourceIdType {
        SIGNATURE,
        FILE_HASH,
        TRANSACTION_DATA,
        NAME
    };

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFile.class);

    public static final long MAX_FILE_SIZE = 500 * 1024 * 1024; // 500MiB
    public static final int CHUNK_SIZE = 1 * 1024 * 1024; // 1MiB
    public static int SHORT_DIGEST_LENGTH = 8;

    protected String filePath;
    protected String hash58;
    private ArrayList<ArbitraryDataFileChunk> chunks;
    private byte[] secret;

    public ArbitraryDataFile() {
    }

    public ArbitraryDataFile(String hash58) {
        this.createDataDirectory();
        this.filePath = ArbitraryDataFile.getOutputFilePath(hash58, false);
        this.chunks = new ArrayList<>();
        this.hash58 = hash58;
    }

    public ArbitraryDataFile(byte[] fileContent) {
        if (fileContent == null) {
            LOGGER.error("fileContent is null");
            return;
        }

        this.hash58 = Base58.encode(Crypto.digest(fileContent));
        LOGGER.trace(String.format("File digest: %s, size: %d bytes", this.hash58, fileContent.length));

        String outputFilePath = getOutputFilePath(this.hash58, true);
        File outputFile = new File(outputFilePath);
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(fileContent);
            this.filePath = outputFilePath;
            // Verify hash
            if (!this.hash58.equals(this.digest58())) {
                LOGGER.error("Hash {} does not match file digest {}", this.hash58, this.digest58());
                this.delete();
                throw new IllegalStateException("Data file digest validation failed");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write data to file");
        }
    }

    public static ArbitraryDataFile fromHash58(String hash58) {
        return new ArbitraryDataFile(hash58);
    }

    public static ArbitraryDataFile fromHash(byte[] hash) {
        return ArbitraryDataFile.fromHash58(Base58.encode(hash));
    }

    public static ArbitraryDataFile fromPath(String path) {
        File file = new File(path);
        if (file.exists()) {
            try {
                byte[] fileContent = Files.readAllBytes(file.toPath());
                byte[] digest = Crypto.digest(fileContent);
                ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);

                // Copy file to data directory if needed
                Path filePath = Paths.get(path);
                if (Files.exists(filePath) && !arbitraryDataFile.isInBaseDirectory(path)) {
                    arbitraryDataFile.copyToDataDirectory(filePath);
                }
                // Or, if it's already in the data directory, we may need to move it
                else if (!filePath.equals(arbitraryDataFile.getFilePath())) {
                    // Wrong path, so relocate
                    Path dest = Paths.get(arbitraryDataFile.getFilePath());
                    FilesystemUtils.moveFile(filePath, dest, true);
                }
                return arbitraryDataFile;

            } catch (IOException e) {
                LOGGER.error("Couldn't compute digest for ArbitraryDataFile");
            }
        }
        return null;
    }

    public static ArbitraryDataFile fromFile(File file) {
        return ArbitraryDataFile.fromPath(file.getPath());
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

    private String copyToDataDirectory(Path sourcePath) {
        if (this.hash58 == null || this.filePath == null) {
            return null;
        }
        String outputFilePath = getOutputFilePath(this.hash58, true);
        sourcePath = sourcePath.toAbsolutePath();
        Path destPath = Paths.get(outputFilePath).toAbsolutePath();
        try {
            return Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING).toString();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to copy file to data directory");
        }
    }

    public static String getOutputFilePath(String hash58, boolean createDirectories) {
        if (hash58 == null) {
            return null;
        }
        String hash58First2Chars = hash58.substring(0, 2);
        String hash58Next2Chars = hash58.substring(2, 4);
        String outputDirectory =  Settings.getInstance().getDataPath() + File.separator + hash58First2Chars + File.separator + hash58Next2Chars;
        Path outputDirectoryPath = Paths.get(outputDirectory);

        if (createDirectories) {
            try {
                Files.createDirectories(outputDirectoryPath);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create data subdirectory");
            }
        }
        return outputDirectory + File.separator + hash58;
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
                LOGGER.error(String.format("ArbitraryDataFile is too large: %d bytes (max size: %d bytes)", fileSize, MAX_FILE_SIZE));
                return ArbitraryDataFile.ValidationResult.FILE_TOO_LARGE;
            }

        } catch (IOException e) {
            return ValidationResult.FILE_NOT_FOUND;
        }

        return ValidationResult.OK;
    }

    public void addChunk(ArbitraryDataFileChunk chunk) {
        this.chunks.add(chunk);
    }

    public void addChunkHashes(byte[] chunks) {
        if (chunks == null || chunks.length == 0) {
            return;
        }
        ByteBuffer byteBuffer = ByteBuffer.wrap(chunks);
        while (byteBuffer.remaining() >= TransactionTransformer.SHA256_LENGTH) {
            byte[] chunkDigest = new byte[TransactionTransformer.SHA256_LENGTH];
            byteBuffer.get(chunkDigest);
            ArbitraryDataFileChunk chunk = ArbitraryDataFileChunk.fromHash(chunkDigest);
            this.addChunk(chunk);
        }
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

                            ArbitraryDataFileChunk chunk = new ArbitraryDataFileChunk(out.toByteArray());
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
            // Use the user-specified temp dir, as it is deterministic, and is more likely to be located on reusable storage hardware
            String baseDir = Settings.getInstance().getTempDataPath();
            Path tempDir = Paths.get(baseDir, "join");
            try {
                Files.createDirectories(tempDir);
            } catch (IOException e) {
                return false;
            }

            // Join the chunks
            Path outputPath = Paths.get(tempDir.toString(), this.chunks.get(0).digest58());
            File outputFile = new File(outputPath.toString());
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                for (ArbitraryDataFileChunk chunk : this.chunks) {
                    File sourceFile = new File(chunk.filePath);
                    BufferedInputStream in = new BufferedInputStream(new FileInputStream(sourceFile));
                    byte[] buffer = new byte[2048];
                    int inSize;
                    while ((inSize = in.read(buffer)) != -1) {
                        out.write(buffer, 0, inSize);
                    }
                    in.close();
                }
                out.close();

                // Copy temporary file to data directory
                this.filePath = this.copyToDataDirectory(outputPath);
                if (FilesystemUtils.pathInsideDataOrTempPath(outputPath)) {
                    Files.delete(outputPath);
                }

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
        // ... but only if it's inside the Qortal data or temp directory
        Path path = Paths.get(this.filePath);
        if (FilesystemUtils.pathInsideDataOrTempPath(path)) {
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
                ArbitraryDataFileChunk chunk = (ArbitraryDataFileChunk) iterator.next();
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
                    if (FilesystemUtils.pathInsideDataOrTempPath(directory)) {
                        Files.delete(directory);
                    }
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
            return Files.readAllBytes(path);
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
        return path.startsWith(basePath);
    }

    public boolean exists() {
        File file = new File(this.filePath);
        return file.exists();
    }

    public boolean chunkExists(byte[] hash) {
        for (ArbitraryDataFileChunk chunk : this.chunks) {
            if (Arrays.equals(hash, chunk.getHash())) {
                return chunk.exists();
            }
        }
        return false;
    }

    public boolean allChunksExist(byte[] chunks) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(chunks);
        while (byteBuffer.remaining() >= TransactionTransformer.SHA256_LENGTH) {
            byte[] chunkHash = new byte[TransactionTransformer.SHA256_LENGTH];
            byteBuffer.get(chunkHash);
            ArbitraryDataFileChunk chunk = ArbitraryDataFileChunk.fromHash(chunkHash);
            if (!chunk.exists()) {
                return false;
            }
        }
        return true;
    }

    public boolean containsChunk(byte[] hash) {
        for (ArbitraryDataFileChunk chunk : this.chunks) {
            if (Arrays.equals(hash, chunk.getHash())) {
                return true;
            }
        }
        return false;
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

    public List<ArbitraryDataFileChunk> getChunks() {
        return this.chunks;
    }

    public byte[] chunkHashes() {
        if (this.chunks != null && this.chunks.size() > 0) {
            // Return null if we only have one chunk, with the same hash as the parent
            if (Arrays.equals(this.digest(), this.chunks.get(0).digest())) {
                return null;
            }

            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                for (ArbitraryDataFileChunk chunk : this.chunks) {
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
                LOGGER.error("Couldn't compute digest for ArbitraryDataFile");
            }
        }
        return null;
    }

    public String digest58() {
        if (this.digest() != null) {
            return Base58.encode(this.digest());
        }
        return null;
    }

    public String shortHash58() {
        if (this.hash58 == null) {
            return null;
        }
        return this.hash58.substring(0, Math.min(this.hash58.length(), SHORT_DIGEST_LENGTH));
    }

    public String getHash58() {
        return this.hash58;
    }

    public byte[] getHash() {
        return Base58.decode(this.hash58);
    }

    public String printChunks() {
        String outputString = "";
        if (this.chunkCount() > 0) {
            for (ArbitraryDataFileChunk chunk : this.chunks) {
                if (outputString.length() > 0) {
                    outputString = outputString.concat(",");
                }
                outputString = outputString.concat(chunk.digest58());
            }
        }
        return outputString;
    }

    public void setSecret(byte[] secret) {
        this.secret = secret;
    }

    public byte[] getSecret() {
        return this.secret;
    }

    @Override
    public String toString() {
        return this.shortHash58();
    }
}
