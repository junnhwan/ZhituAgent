package com.zhituagent.rag;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class KnowledgeStoreIds {

    private KnowledgeStoreIds() {
    }

    public static String toEmbeddingId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
