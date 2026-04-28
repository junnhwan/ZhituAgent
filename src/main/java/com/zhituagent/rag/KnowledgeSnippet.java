package com.zhituagent.rag;

public record KnowledgeSnippet(
        String source,
        String chunkId,
        double score,
        String content,
        double denseScore,
        double rerankScore
) {

    public KnowledgeSnippet(String source, String chunkId, double score, String content) {
        this(source, chunkId, score, content, score, 0.0);
    }
}
