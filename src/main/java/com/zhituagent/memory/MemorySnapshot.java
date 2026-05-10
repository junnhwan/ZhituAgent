package com.zhituagent.memory;

import java.util.List;

public record MemorySnapshot(
        String summary,
        List<ChatMessageRecord> recentMessages,
        List<String> facts,
        SummarySource summarySource
) {

    public MemorySnapshot(String summary, List<ChatMessageRecord> recentMessages) {
        this(summary, recentMessages, List.of(), SummarySource.NONE);
    }

    public MemorySnapshot(String summary, List<ChatMessageRecord> recentMessages, List<String> facts) {
        this(summary, recentMessages, facts, SummarySource.NONE);
    }

    public MemorySnapshot {
        summary = summary == null ? "" : summary;
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        facts = facts == null ? List.of() : List.copyOf(facts);
        summarySource = summarySource == null ? SummarySource.NONE : summarySource;
    }
}
