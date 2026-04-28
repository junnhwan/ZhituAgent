package com.zhituagent.memory;

import com.zhituagent.metrics.MemoryMetricsRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class MemoryService {

    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    private final MemoryStore memoryStore;
    private final MessageSummaryCompressor compressor;
    private final MemoryLock memoryLock;
    private final MemoryMetricsRecorder memoryMetricsRecorder;

    @Autowired
    public MemoryService(MemoryStore memoryStore, MemoryLock memoryLock, MemoryMetricsRecorder memoryMetricsRecorder) {
        this(memoryStore, new MessageSummaryCompressor(4, 6), memoryLock, memoryMetricsRecorder);
    }

    public MemoryService(MessageSummaryCompressor compressor) {
        this(new InMemoryMemoryStore(), compressor, new NoopMemoryLock(), MemoryMetricsRecorder.noop());
    }

    MemoryService(MemoryStore memoryStore, MessageSummaryCompressor compressor) {
        this(memoryStore, compressor, new NoopMemoryLock(), MemoryMetricsRecorder.noop());
    }

    MemoryService(MemoryStore memoryStore,
                  MessageSummaryCompressor compressor,
                  MemoryLock memoryLock,
                  MemoryMetricsRecorder memoryMetricsRecorder) {
        this.memoryStore = memoryStore;
        this.compressor = compressor;
        this.memoryLock = memoryLock;
        this.memoryMetricsRecorder = memoryMetricsRecorder;
    }

    public void append(String sessionId, String role, String content) {
        memoryStore.append(sessionId, new ChatMessageRecord(role, content, OffsetDateTime.now()));
    }

    public MemorySnapshot snapshot(String sessionId) {
        List<ChatMessageRecord> messages = memoryStore.list(sessionId);
        if (!compressor.shouldCompress(messages)) {
            memoryMetricsRecorder.recordCompression("not_needed", storeType());
            return compressor.compress(messages);
        }

        String lockToken = memoryLock.tryAcquire(sessionId, LOCK_TTL);
        if (lockToken == null) {
            memoryMetricsRecorder.recordCompression("lock_miss", storeType());
            return compressor.recentOnly(messages);
        }

        try {
            MemorySnapshot snapshot = compressor.compress(messages);
            memoryMetricsRecorder.recordCompression("compressed", storeType());
            return snapshot;
        } finally {
            memoryLock.release(sessionId, lockToken);
        }
    }

    private String storeType() {
        return memoryStore instanceof RedisMemoryStore ? "redis" : "memory";
    }
}
