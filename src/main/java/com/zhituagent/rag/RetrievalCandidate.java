package com.zhituagent.rag;

public record RetrievalCandidate(
        String source,
        String chunkId,
        double score,
        String content,
        double denseScore,
        double lexicalScore
) {

    public RetrievalCandidate(String source, String chunkId, double denseScore, String content) {
        this(source, chunkId, denseScore, content, denseScore, 0.0);
    }
}
