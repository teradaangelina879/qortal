package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.arbitrary.ArbitraryDataCreatePatch;
import org.qortal.arbitrary.ArbitraryDataDigest;
import org.qortal.arbitrary.ArbitraryDataMerge;
import org.qortal.crypto.Crypto;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.*;

public class ArbitraryDataMergeTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testCreateAndMergePatch() throws IOException, DataException {
        Path path1 = Paths.get("src/test/resources/arbitrary/demo1");
        Path path2 = Paths.get("src/test/resources/arbitrary/demo2");

        // Create a patch using the differences in path2 compared with path1
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(path1, path2, new byte[16]);
        patch.create();
        Path patchPath = patch.getFinalPath();
        assertTrue(Files.exists(patchPath));

        // Check that lorem1, 2, 4, and 5 exist
        assertTrue(Files.exists(Paths.get(patchPath.toString(), "lorem1.txt")));
        assertTrue(Files.exists(Paths.get(patchPath.toString(), "lorem2.txt")));
        assertTrue(Files.exists(Paths.get(patchPath.toString(), "dir1", "lorem4.txt")));
        assertTrue(Files.exists(Paths.get(patchPath.toString(), "dir1", "dir2", "lorem5.txt")));

        // Ensure that lorem3 doesn't exist, as this file is identical in the original paths
        assertFalse(Files.exists(Paths.get(patchPath.toString(), "lorem3.txt")));

        // Ensure that the patch files differ from the first path (except for lorem3, which is missing)
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path1.toString(), "lorem1.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "lorem1.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path1.toString(), "lorem2.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "lorem2.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path1.toString(), "dir1", "lorem4.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "dir1", "lorem4.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path1.toString(), "dir1", "dir2", "lorem5.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "dir1", "dir2", "lorem5.txt").toFile())
        ));

        // Ensure that the patch files differ from the second path (except for lorem3, which is missing)
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path2.toString(), "lorem1.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "lorem1.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path2.toString(), "lorem2.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "lorem2.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path2.toString(), "dir1", "lorem4.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "dir1", "lorem4.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path2.toString(), "dir1", "dir2", "lorem5.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "dir1", "dir2", "lorem5.txt").toFile())
        ));

        // Now merge the patch with the original path
        ArbitraryDataMerge merge = new ArbitraryDataMerge(path1, patchPath, "unified-diff");
        merge.compute();
        Path finalPath = merge.getMergePath();

        // Ensure that all files exist in the final path (including lorem3)
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "lorem1.txt")));
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "lorem2.txt")));
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "lorem3.txt")));
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "dir1", "lorem4.txt")));
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "dir1", "dir2", "lorem5.txt")));

        // Ensure that the files match those in path2 exactly
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "lorem1.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "lorem1.txt").toFile())
        );
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "lorem2.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "lorem2.txt").toFile())
        );
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "lorem3.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "lorem3.txt").toFile())
        );
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "dir1", "lorem4.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "dir1", "lorem4.txt").toFile())
        );
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "dir1", "dir2", "lorem5.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "dir1", "dir2", "lorem5.txt").toFile())
        );

        // Also check that the directory digests match
        ArbitraryDataDigest path2Digest = new ArbitraryDataDigest(path2);
        path2Digest.compute();
        ArbitraryDataDigest finalPathDigest = new ArbitraryDataDigest(path2);
        finalPathDigest.compute();
        assertEquals(path2Digest.getHash58(), finalPathDigest.getHash58());
    }

    @Test
    public void testIdenticalPaths() throws IOException, DataException {
        Path path = Paths.get("src/test/resources/arbitrary/demo1");

        // Create a patch from two identical paths
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(path, path, new byte[16]);

        // Ensure that an exception is thrown due to matching states
        try {
            patch.create();
            fail("Creating patch should fail due to matching states");

        } catch (IllegalStateException expectedException) {
            assertEquals("Current state matches previous state. Nothing to do.", expectedException.getMessage());
        }

    }

}
