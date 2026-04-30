package com.zhituagent.rag;

/**
 * Outcome of a Self-RAG sufficiency evaluation. Mirrors the {@code retrieve}
 * critique token from Asai et al. 2023: did the snippets give us enough to
 * answer, and if not, what should we re-ask the retriever?
 *
 * @param sufficient     true if the LLM (or fallback heuristic) judges current
 *                       snippets enough to answer the user
 * @param rewrittenQuery alternate query for the next retrieval round; blank
 *                       means "no rewrite suggestion"
 * @param reason         short human-readable rationale, primarily for trace UI
 */
public record SelfRagDecision(
        boolean sufficient,
        String rewrittenQuery,
        String reason
) {
    public static SelfRagDecision sufficient(String reason) {
        return new SelfRagDecision(true, "", reason == null ? "" : reason);
    }

    public static SelfRagDecision insufficient(String rewrittenQuery, String reason) {
        return new SelfRagDecision(
                false,
                rewrittenQuery == null ? "" : rewrittenQuery,
                reason == null ? "" : reason
        );
    }

    public boolean hasRewriteSuggestion() {
        return rewrittenQuery != null && !rewrittenQuery.isBlank();
    }
}
