package com.zhituagent.memory;

import java.time.OffsetDateTime;

public record ConversationSummaryState(
        String summaryMarkdown,
        int summarizedMessageCount,
        OffsetDateTime updatedAt,
        String modelName,
        long inputTokenEstimate,
        long outputTokenEstimate
) {

    public ConversationSummaryState {
        summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown;
        summarizedMessageCount = Math.max(0, summarizedMessageCount);
        updatedAt = updatedAt == null ? OffsetDateTime.now() : updatedAt;
        modelName = modelName == null ? "" : modelName;
        inputTokenEstimate = Math.max(0, inputTokenEstimate);
        outputTokenEstimate = Math.max(0, outputTokenEstimate);
    }
}
