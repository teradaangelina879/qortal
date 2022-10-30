package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.arbitrary.misc.Service;
import org.qortal.arbitrary.misc.Service.ValidationResult;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryServiceTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testDefaultValidation() throws IOException {
        // We don't validate the ARBITRARY_DATA service specifically, so we can use it to test the default validation method
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write to temp path
        Path path = Files.createTempFile("testDefaultValidation", null);
        path.toFile().deleteOnExit();
        Files.write(path, data, StandardOpenOption.CREATE);

        Service service = Service.ARBITRARY_DATA;
        assertFalse(service.isValidationRequired());
        // Test validation anyway to ensure that no exception is thrown
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsite() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsite");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "index.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is an index file in the root
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithoutIndexFile() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFile");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "data1.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.MISSING_INDEX_FILE, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithoutIndexFileInRoot() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFileInRoot");
        path.toFile().deleteOnExit();
        Files.createDirectories(Paths.get(path.toString(), "directory"));
        Files.write(Paths.get(path.toString(), "directory", "index.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.MISSING_INDEX_FILE, service.validate(path));
    }

}
