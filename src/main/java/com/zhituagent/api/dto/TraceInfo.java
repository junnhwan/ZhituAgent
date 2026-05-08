package com.zhituagent.api.dto;

import com.zhituagent.trace.Span;

import java.util.List;

public record TraceInfo(
        String path,
        boolean retrievalHit,
        boolean toolUsed,
        String retrievalMode,
        String contextStrategy,
        String requestId,
        long latencyMs,
        int snippetCount,
        String topSource,
        double topScore,
        int retrievalCandidateCount,
        String rerankModel,
        double rerankTopScore,
        int factCount,
        long inputTokenEstimate,
        long outputTokenEstimate,
        List<String> retrievedSources,
        List<SnippetInfo> retrievedSnippets,
        String traceId,
        List<Span> spans
) {

    public TraceInfo {
        retrievedSources = retrievedSources == null ? List.of() : List.copyOf(retrievedSources);
        retrievedSnippets = retrievedSnippets == null ? List.of() : List.copyOf(retrievedSnippets);
        traceId = traceId == null ? "" : traceId;
        spans = spans == null ? List.of() : List.copyOf(spans);
    }
}
