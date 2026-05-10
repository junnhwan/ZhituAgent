package com.zhituagent.memory;

import com.zhituagent.config.MemorySummaryProperties;
import com.zhituagent.metrics.MemoryMetricsRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class MemoryService {

    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    private final MemoryStore memoryStore;
    private final MessageSummaryCompressor compressor;
    private final FactExtractor factExtractor;
    private final MemoryLock memoryLock;
    private final MemoryMetricsRecorder memoryMetricsRecorder;
    private final SummaryStore summaryStore;
    private final ConversationSummarizer llmSummarizer;
    private final RuleBasedConversationSummarizer ruleSummarizer;
    private final MemorySummaryProperties summaryProperties;

    @Autowired
    public MemoryService(MemoryStore memoryStore,
                         MemoryLock memoryLock,
                         MemoryMetricsRecorder memoryMetricsRecorder,
                         SummaryStore summaryStore,
                         Optional<ConversationSummarizer> llmSummarizer,
                         MemorySummaryProperties summaryProperties) {
        this(
                memoryStore,
                new MessageSummaryCompressor(4, 6),
                new FactExtractor(),
                memoryLock,
                memoryMetricsRecorder,
                summaryStore,
                llmSummarizer.orElse(null),
                new RuleBasedConversationSummarizer(summaryProperties),
                summaryProperties
        );
    }

    public MemoryService(MessageSummaryCompressor compressor) {
        this(
                new InMemoryMemoryStore(),
                compressor,
                new FactExtractor(),
                new NoopMemoryLock(),
                MemoryMetricsRecorder.noop()
        );
    }

    MemoryService(MemoryStore memoryStore, MessageSummaryCompressor compressor) {
        this(memoryStore, compressor, new FactExtractor(), new NoopMemoryLock(), MemoryMetricsRecorder.noop());
    }

    MemoryService(MemoryStore memoryStore,
                  MessageSummaryCompressor compressor,
                  MemoryLock memoryLock,
                  MemoryMetricsRecorder memoryMetricsRecorder) {
        this(memoryStore, compressor, new FactExtractor(), memoryLock, memoryMetricsRecorder);
    }

    MemoryService(MemoryStore memoryStore,
                  MessageSummaryCompressor compressor,
                  FactExtractor factExtractor,
                  MemoryLock memoryLock,
                  MemoryMetricsRecorder memoryMetricsRecorder) {
        MemorySummaryProperties summaryProperties = new MemorySummaryProperties();
        this.memoryStore = memoryStore;
        this.compressor = compressor;
        this.factExtractor = factExtractor;
        this.memoryLock = memoryLock;
        this.memoryMetricsRecorder = memoryMetricsRecorder;
        this.summaryStore = new InMemorySummaryStore();
        this.llmSummarizer = null;
        this.ruleSummarizer = new RuleBasedConversationSummarizer(summaryProperties);
        this.summaryProperties = summaryProperties;
    }

    MemoryService(MemoryStore memoryStore,
                  MessageSummaryCompressor compressor,
                  FactExtractor factExtractor,
                  MemoryLock memoryLock,
                  MemoryMetricsRecorder memoryMetricsRecorder,
                  SummaryStore summaryStore,
                  ConversationSummarizer llmSummarizer,
                  RuleBasedConversationSummarizer ruleSummarizer,
                  MemorySummaryProperties summaryProperties) {
        this.memoryStore = memoryStore;
        this.compressor = compressor;
        this.factExtractor = factExtractor;
        this.memoryLock = memoryLock;
        this.memoryMetricsRecorder = memoryMetricsRecorder;
        this.summaryStore = summaryStore;
        this.llmSummarizer = llmSummarizer;
        this.ruleSummarizer = ruleSummarizer;
        this.summaryProperties = summaryProperties;
    }

    public void append(String sessionId, String role, String content) {
        memoryStore.append(sessionId, new ChatMessageRecord(role, content, OffsetDateTime.now()));
    }

    public List<ChatMessageRecord> listAll(String sessionId) {
        return memoryStore.list(sessionId);
    }

    // 快照入口：读全量消息 → 提取稳定事实 → 加分布式锁做历史压缩 → 输出 MemorySnapshot
    // 压缩加锁防并发（同一 session 多请求同时触发压缩时只有一个做压缩，其余走 recentOnly 降级）
    public MemorySnapshot snapshot(String sessionId) {
        List<ChatMessageRecord> messages = memoryStore.list(sessionId);
        List<String> facts = factExtractor.extract(messages);
        if (summaryProperties.isEnabled()) {
            return snapshotWithSummaryState(sessionId, messages, facts);
        }
        if (!compressor.shouldCompress(messages)) {
            memoryMetricsRecorder.recordCompression("not_needed", storeType());
            return withFacts(compressor.compress(messages), facts);
        }

        String lockToken = memoryLock.tryAcquire(sessionId, LOCK_TTL);
        if (lockToken == null) {
            memoryMetricsRecorder.recordCompression("lock_miss", storeType());
            return withFacts(compressor.recentOnly(messages), facts);
        }

        try {
            MemorySnapshot snapshot = compressor.compress(messages);
            memoryMetricsRecorder.recordCompression("compressed", storeType());
            return withFacts(snapshot, facts);
        } finally {
            memoryLock.release(sessionId, lockToken);
        }
    }

    private MemorySnapshot withFacts(MemorySnapshot snapshot, List<String> facts) {
        return new MemorySnapshot(snapshot.summary(), snapshot.recentMessages(), facts, snapshot.summarySource());
    }

    private MemorySnapshot snapshotWithSummaryState(String sessionId, List<ChatMessageRecord> messages, List<String> facts) {
        ConversationSummaryState state = summaryStore.get(sessionId).orElse(null);
        int summarizedCount = safeSummarizedCount(state, messages.size());
        int unsummarizedCount = messages.size() - summarizedCount;
        List<ChatMessageRecord> recentMessages = recentMessages(messages);
        String existingSummary = state == null ? "" : state.summaryMarkdown();

        if (unsummarizedCount < Math.max(1, summaryProperties.getTriggerMessageCount())) {
            memoryMetricsRecorder.recordSummary(
                    "disabled",
                    state == null ? "none" : state.modelName(),
                    0,
                    state == null ? 0 : state.inputTokenEstimate(),
                    state == null ? 0 : state.outputTokenEstimate()
            );
            return new MemorySnapshot(existingSummary, recentMessages, facts, sourceOf(state));
        }

        String lockToken = memoryLock.tryAcquire(sessionId, LOCK_TTL);
        if (lockToken == null) {
            memoryMetricsRecorder.recordSummary("lock_miss", modelNameOf(state), 0, 0, 0);
            return new MemorySnapshot(existingSummary, recentMessages, facts, sourceOf(state));
        }

        try {
            int compressUntilExclusive = Math.max(summarizedCount, messages.size() - Math.max(0, summaryProperties.getMaxRecentMessages()));
            List<ChatMessageRecord> messagesToCompress = messages.subList(summarizedCount, compressUntilExclusive);
            if (messagesToCompress.isEmpty()) {
                return new MemorySnapshot(existingSummary, recentMessages, facts, sourceOf(state));
            }

            SummaryResult result = llmSummarizer == null
                    ? SummaryResult.disabled()
                    : llmSummarizer.summarize(existingSummary, messagesToCompress);
            recordSummaryResult(result);
            if (isUsableLlmSummary(result)) {
                ConversationSummaryState newState = new ConversationSummaryState(
                        result.summaryMarkdown(),
                        compressUntilExclusive,
                        OffsetDateTime.now(),
                        result.modelName(),
                        result.inputTokenEstimate(),
                        result.outputTokenEstimate()
                );
                summaryStore.save(sessionId, newState);
                return new MemorySnapshot(result.summaryMarkdown(), recentMessages, facts, SummarySource.LLM);
            }

            return fallbackRuleSummary(existingSummary, messages, facts);
        } finally {
            memoryLock.release(sessionId, lockToken);
        }
    }

    private MemorySnapshot fallbackRuleSummary(String existingSummary, List<ChatMessageRecord> messages, List<String> facts) {
        if (!summaryProperties.isFallbackOnFailure()) {
            return new MemorySnapshot(existingSummary, recentMessages(messages), facts, existingSummary.isBlank() ? SummarySource.NONE : SummarySource.LLM);
        }
        MemorySnapshot snapshot = ruleSummarizer.fallbackSnapshot(existingSummary, messages, facts);
        memoryMetricsRecorder.recordSummary("fallback_rule", "rule", 0, 0, snapshot.summary().length());
        return snapshot;
    }

    private void recordSummaryResult(SummaryResult result) {
        String outcome = switch (result.outcome()) {
            case SUCCESS -> "success";
            case TIMEOUT -> "timeout";
            case ERROR -> "error";
            case DISABLED -> "disabled";
            case FALLBACK_RULE -> "fallback_rule";
        };
        memoryMetricsRecorder.recordSummary(
                outcome,
                result.modelName(),
                result.latencyMs(),
                result.inputTokenEstimate(),
                result.outputTokenEstimate()
        );
    }

    private boolean isUsableLlmSummary(SummaryResult result) {
        return result != null
                && result.outcome() == SummaryOutcome.SUCCESS
                && SummaryMarkdownValidator.isValid(result.summaryMarkdown(), summaryProperties.getMaxOutputChars());
    }

    private int safeSummarizedCount(ConversationSummaryState state, int messageCount) {
        if (state == null) {
            return 0;
        }
        return Math.max(0, Math.min(state.summarizedMessageCount(), messageCount));
    }

    private List<ChatMessageRecord> recentMessages(List<ChatMessageRecord> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int splitIndex = Math.max(0, messages.size() - Math.max(0, summaryProperties.getMaxRecentMessages()));
        return List.copyOf(messages.subList(splitIndex, messages.size()));
    }

    private SummarySource sourceOf(ConversationSummaryState state) {
        return state == null || state.summaryMarkdown().isBlank() ? SummarySource.NONE : SummarySource.LLM;
    }

    private String modelNameOf(ConversationSummaryState state) {
        return state == null || state.modelName().isBlank() ? "unknown" : state.modelName();
    }

    private String storeType() {
        return memoryStore instanceof RedisMemoryStore ? "redis" : "memory";
    }
}
