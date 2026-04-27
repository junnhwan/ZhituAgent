package com.zhituagent.memory;

import java.util.List;

public class MessageSummaryCompressor {

    private final int maxRecentMessages;
    private final int compressionThreshold;

    public MessageSummaryCompressor(int maxRecentMessages, int compressionThreshold) {
        this.maxRecentMessages = maxRecentMessages;
        this.compressionThreshold = compressionThreshold;
    }

    public MemorySnapshot compress(List<ChatMessageRecord> messages) {
        if (messages.size() < compressionThreshold) {
            return new MemorySnapshot("", List.copyOf(messages));
        }

        int splitIndex = Math.max(0, messages.size() - maxRecentMessages);
        List<ChatMessageRecord> olderMessages = messages.subList(0, splitIndex);
        List<ChatMessageRecord> recentMessages = messages.subList(splitIndex, messages.size());

        String summary = olderMessages.isEmpty()
                ? ""
                : "Earlier conversation summary: " + olderMessages.stream()
                .map(message -> message.role().toUpperCase() + ": " + abbreviate(message.content()))
                .reduce((left, right) -> left + " | " + right)
                .orElse("");

        return new MemorySnapshot(summary, List.copyOf(recentMessages));
    }

    private String abbreviate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 24 ? content : content.substring(0, 24) + "...";
    }
}
