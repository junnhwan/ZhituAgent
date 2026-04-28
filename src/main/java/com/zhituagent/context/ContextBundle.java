package com.zhituagent.context;

import com.zhituagent.memory.ChatMessageRecord;

import java.util.List;

public record ContextBundle(
        String systemPrompt,
        String summary,
        List<ChatMessageRecord> recentMessages,
        List<String> facts,
        String currentMessage,
        List<String> modelMessages,
        String contextStrategy
) {
}
