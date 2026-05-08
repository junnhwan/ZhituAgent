package com.zhituagent.api.dto;

public record SnippetInfo(
        String source,
        String content,
        double score,
        double denseScore,
        double rerankScore
) {
}
