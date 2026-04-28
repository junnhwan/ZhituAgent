package com.zhituagent.rag;

import java.util.List;

public record RagRetrievalResult(
        List<KnowledgeSnippet> snippets,
        String retrievalMode,
        int retrievalCandidateCount,
        String rerankModel,
        double rerankTopScore
) {

    public RagRetrievalResult {
        snippets = snippets == null ? List.of() : List.copyOf(snippets);
        retrievalMode = retrievalMode == null || retrievalMode.isBlank() ? "none" : retrievalMode;
        rerankModel = rerankModel == null ? "" : rerankModel;
    }
}
