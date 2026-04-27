package com.zhituagent.rag;

public record KnowledgeChunk(
        String source,
        String chunkId,
        String content
) {
}
