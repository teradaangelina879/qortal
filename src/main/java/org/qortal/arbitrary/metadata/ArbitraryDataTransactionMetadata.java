package org.qortal.arbitrary.metadata;

import org.json.JSONArray;
import org.json.JSONObject;
import org.qortal.arbitrary.misc.Category;
import org.qortal.repository.DataException;
import org.qortal.utils.Base58;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ArbitraryDataTransactionMetadata extends ArbitraryDataMetadata {

    private List<byte[]> chunks;
    private String title;
    private String description;
    private String tags;
    private Category category;

    public ArbitraryDataTransactionMetadata(Path filePath) {
        super(filePath);

    }

    @Override
    protected void readJson() throws DataException {
        if (this.jsonString == null) {
            throw new DataException("Transaction metadata JSON string is null");
        }

        JSONObject metadata = new JSONObject(this.jsonString);

        if (metadata.has("title")) {
            this.title = metadata.getString("title");
        }
        if (metadata.has("description")) {
            this.description = metadata.getString("description");
        }
        if (metadata.has("tags")) {
            this.tags = metadata.getString("tags");
        }
        if (metadata.has("category")) {
            this.category = Category.valueOf(metadata.getString("category"));
        }

        List<byte[]> chunksList = new ArrayList<>();
        if (metadata.has("chunks")) {
            JSONArray chunks = metadata.getJSONArray("chunks");
            if (chunks != null) {
                for (int i=0; i<chunks.length(); i++) {
                    String chunk = chunks.getString(i);
                    if (chunk != null) {
                        chunksList.add(Base58.decode(chunk));
                    }
                }
            }
            this.chunks = chunksList;
        }
    }

    @Override
    protected void buildJson() {
        JSONObject outer = new JSONObject();

        if (this.title != null && !this.title.isEmpty()) {
            outer.put("title", this.title);
        }
        if (this.description != null && !this.description.isEmpty()) {
            outer.put("description", this.description);
        }
        if (this.tags != null && !this.tags.isEmpty()) {
            outer.put("tags", this.tags);
        }
        if (this.category != null) {
            outer.put("category", this.category.toString());
        }

        JSONArray chunks = new JSONArray();
        if (this.chunks != null) {
            for (byte[] chunk : this.chunks) {
                chunks.put(Base58.encode(chunk));
            }
        }
        outer.put("chunks", chunks);

        this.jsonString = outer.toString(2);
        LOGGER.trace("Transaction metadata: {}", this.jsonString);
    }


    public void setChunks(List<byte[]> chunks) {
        this.chunks = chunks;
    }

    public List<byte[]> getChunks() {
        return this.chunks;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTitle() {
        return this.title;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDescription() {
        return this.description;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getTags() {
        return this.tags;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Category getCategory() {
        return this.category;
    }

    public boolean containsChunk(byte[] chunk) {
        for (byte[] c : this.chunks) {
            if (Arrays.equals(c, chunk)) {
                return true;
            }
        }
        return false;
    }

}
