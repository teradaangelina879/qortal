package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.arbitrary.misc.Service;
import org.qortal.arbitrary.misc.Service.ValidationResult;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryServiceTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testDefaultValidation() {
        // We don't validate websites yet, but we still want to test the default validation method
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        Service service = Service.WEBSITE;
        assertFalse(service.isValidationRequired());
        // Test validation anyway to ensure that no exception is thrown
        assertEquals(ValidationResult.OK, service.validate(data, data.length));
    }

    @Test
    public void testValidQortalMetadata() {
        // Metadata is to describe an arbitrary resource (title, description, tags, etc)
        String dataString = "{\"title\":\"Test Title\", \"description\":\"Test description\", \"tags\":[\"test\"]}";
        byte[] data = dataString.getBytes();

        Service service = Service.QORTAL_METADATA;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.OK, service.validate(data, data.length));
    }

    @Test
    public void testQortalMetadataMissingKeys() {
        // Metadata is to describe an arbitrary resource (title, description, tags, etc)
        String dataString = "{\"description\":\"Test description\", \"tags\":[\"test\"]}";
        byte[] data = dataString.getBytes();

        Service service = Service.QORTAL_METADATA;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.MISSING_KEYS, service.validate(data, data.length));
    }

    @Test
    public void testQortalMetadataTooLarge() {
        // Metadata is to describe an arbitrary resource (title, description, tags, etc)
        String dataString = "{\"title\":\"Test Title\", \"description\":\"Test description\", \"tags\":[\"test\"]}";
        byte[] data = dataString.getBytes();
        long totalResourceSize = 11*1024L; // Larger than allowed 10kiB

        Service service = Service.QORTAL_METADATA;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.EXCEEDS_SIZE_LIMIT, service.validate(data, totalResourceSize));
    }

}
