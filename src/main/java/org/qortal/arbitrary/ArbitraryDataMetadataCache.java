package org.qortal.arbitrary;

import org.json.JSONObject;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.nio.file.Path;

public class ArbitraryDataMetadataCache extends ArbitraryDataMetadata {

    private byte[] signature;
    private long timestamp;

    public ArbitraryDataMetadataCache(Path filePath) {
        super(filePath);

    }

    @Override
    protected String fileName() {
        return "cache";
    }

    @Override
    protected void readJson() {
        if (this.jsonString == null) {
            throw new IllegalStateException("Patch JSON string is null");
        }

        JSONObject cache = new JSONObject(this.jsonString);
        if (cache.has("signature")) {
            String sig = cache.getString("signature");
            if (sig != null) {
                this.signature = Base58.decode(sig);
            }
        }
        if (cache.has("timestamp")) {
            this.timestamp = cache.getLong("timestamp");
        }
    }

    @Override
    protected void buildJson() {
        JSONObject patch = new JSONObject();
        patch.put("signature", Base58.encode(this.signature));
        patch.put("timestamp", this.timestamp);

        this.jsonString = patch.toString(2);
        LOGGER.info("Cache metadata: {}", this.jsonString);
    }


    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    public byte[] getSignature() {
        return this.signature;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getTimestamp() {
        return this.timestamp;
    }

}
