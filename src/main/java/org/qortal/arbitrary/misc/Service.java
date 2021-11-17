package org.qortal.arbitrary.misc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.qortal.transaction.Transaction;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public enum Service {
    AUTO_UPDATE(1, false, null, null),
    ARBITRARY_DATA(100, false, null, null),
    WEBSITE(200, false, null, null),
    GIT_REPOSITORY(300, false, null, null),
    IMAGE(400, true, 10*1024*1024L, null),
    THUMBNAIL(410, true, 500*1024L, null),
    VIDEO(500, false, null, null),
    AUDIO(600, false, null, null),
    BLOG(700, false, null, null),
    BLOG_POST(777, false, null, null),
    BLOG_COMMENT(778, false, null, null),
    DOCUMENT(800, false, null, null),
    LIST(900, true, null, null),
    PLAYLIST(910, true, null, null),
    APP(1000, false, null, null),
    METADATA(1100, false, null, null),
    QORTAL_METADATA(1111, true, 10*1024L, Arrays.asList("title", "description", "tags"));

    public final int value;
    private final boolean requiresValidation;
    private final Long maxSize;
    private final List<String> requiredKeys;

    private static final Logger LOGGER = LogManager.getLogger(Service.class);

    private static final Map<Integer, Service> map = stream(Service.values())
            .collect(toMap(service -> service.value, service -> service));

    Service(int value, boolean requiresValidation, Long maxSize, List<String> requiredKeys) {
        this.value = value;
        this.requiresValidation = requiresValidation;
        this.maxSize = maxSize;
        this.requiredKeys = requiredKeys;
    }

    public ValidationResult validate(byte[] data, long size) {
        if (!this.isValidationRequired()) {
            return ValidationResult.OK;
        }

        // Validate max size if needed
        if (this.maxSize != null) {
            if (size > this.maxSize || data.length > this.maxSize) {
                return ValidationResult.EXCEEDS_SIZE_LIMIT;
            }
        }

        // Validate required keys if needed
        if (this.requiredKeys != null) {
            JSONObject json = Service.toJsonObject(data);
            for (String key : this.requiredKeys) {
                if (!json.has(key)) {
                    return ValidationResult.MISSING_KEYS;
                }
            }
        }

        // Validation passed
        return ValidationResult.OK;
    }

    public boolean isValidationRequired() {
        return this.requiresValidation;
    }

    public static Service valueOf(int value) {
        return map.get(value);
    }

    public static JSONObject toJsonObject(byte[] data) {
        String dataString = new String(data);
        return new JSONObject(dataString);
    }

    public enum ValidationResult {
        OK(1),
        MISSING_KEYS(2),
        EXCEEDS_SIZE_LIMIT(3);

        public final int value;

        private static final Map<Integer, Transaction.ValidationResult> map = stream(Transaction.ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

        ValidationResult(int value) {
            this.value = value;
        }

        public static Transaction.ValidationResult valueOf(int value) {
            return map.get(value);
        }
    }
}
