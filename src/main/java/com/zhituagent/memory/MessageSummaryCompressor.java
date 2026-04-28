package com.zhituagent.memory;

import java.util.List;

public class MessageSummaryCompressor {

    private final int maxRecentMessages;
    private final int compressionThreshold;

    public MessageSummaryCompressor(int maxRecentMessages, int compressionThreshold) {
        this.maxRecentMessages = maxRecentMessages;
        this.compressionThreshold = compressionThreshold;
    }

    public boolean shouldCompress(List<ChatMessageRecord> messages) {
        return messages != null && messages.size() >= compressionThreshold;
    }

    public MemorySnapshot compress(List<ChatMessageRecord> messages) {
        if (!shouldCompress(messages)) {
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

    public MemorySnapshot recentOnly(List<ChatMessageRecord> messages) {
        if (messages == null || messages.isEmpty()) {
            return new MemorySnapshot("", List.of());
        }
        int splitIndex = Math.max(0, messages.size() - maxRecentMessages);
        return new MemorySnapshot("", List.copyOf(messages.subList(splitIndex, messages.size())));
    }

    private String abbreviate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 24 ? content : content.substring(0, 24) + "...";
    }
}
