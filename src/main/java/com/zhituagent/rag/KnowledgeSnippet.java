package com.zhituagent.rag;

public record KnowledgeSnippet(
        String source,
        String chunkId,
        double score,
        String content
) {
}
