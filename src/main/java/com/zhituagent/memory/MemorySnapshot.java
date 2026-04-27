package com.zhituagent.memory;

import java.util.List;

public record MemorySnapshot(
        String summary,
        List<ChatMessageRecord> recentMessages
) {
}
