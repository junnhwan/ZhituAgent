package com.zhituagent.memory;

import java.util.List;

public record MemorySnapshot(
        String summary,
        List<ChatMessageRecord> recentMessages,
        List<String> facts
) {

    public MemorySnapshot(String summary, List<ChatMessageRecord> recentMessages) {
        this(summary, recentMessages, List.of());
    }
}
