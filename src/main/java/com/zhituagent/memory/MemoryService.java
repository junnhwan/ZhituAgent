package com.zhituagent.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class MemoryService {

    private final MemoryStore memoryStore;
    private final MessageSummaryCompressor compressor;

    @Autowired
    public MemoryService(MemoryStore memoryStore) {
        this(memoryStore, new MessageSummaryCompressor(4, 6));
    }

    public MemoryService(MessageSummaryCompressor compressor) {
        this(new InMemoryMemoryStore(), compressor);
    }

    MemoryService(MemoryStore memoryStore, MessageSummaryCompressor compressor) {
        this.memoryStore = memoryStore;
        this.compressor = compressor;
    }

    public void append(String sessionId, String role, String content) {
        memoryStore.append(sessionId, new ChatMessageRecord(role, content, OffsetDateTime.now()));
    }

    public MemorySnapshot snapshot(String sessionId) {
        List<ChatMessageRecord> messages = memoryStore.list(sessionId);
        return compressor.compress(messages);
    }
}
