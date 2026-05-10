package com.zhituagent.memory;

import com.zhituagent.config.MemorySummaryProperties;
import com.zhituagent.metrics.MemoryMetricsRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {

    @Test
    void shouldCompressOlderMessagesIntoSummaryAndKeepRecentMessages() {
        MemoryService memoryService = new MemoryService(new MessageSummaryCompressor(4, 6));

        memoryService.append("sess_1", "user", "第一轮问题");
        memoryService.append("sess_1", "assistant", "第一轮回答");
        memoryService.append("sess_1", "user", "第二轮问题");
        memoryService.append("sess_1", "assistant", "第二轮回答");
        memoryService.append("sess_1", "user", "第三轮问题");
        memoryService.append("sess_1", "assistant", "第三轮回答");

        MemorySnapshot snapshot = memoryService.snapshot("sess_1");

        assertThat(snapshot.summary()).contains("Earlier conversation summary");
        assertThat(snapshot.recentMessages()).hasSize(4);
        assertThat(snapshot.recentMessages().getFirst().content()).isEqualTo("第二轮问题");
        assertThat(snapshot.recentMessages().getLast().content()).isEqualTo("第三轮回答");
    }

    @Test
    void shouldSkipCompressionWhenLockIsUnavailableButKeepRecentMessages() {
        MemoryService memoryService = new MemoryService(
                new InMemoryMemoryStore(),
                new MessageSummaryCompressor(4, 6),
                (sessionId, ttl) -> null,
                MemoryMetricsRecorder.noop()
        );

        memoryService.append("sess_2", "user", "第一轮问题");
        memoryService.append("sess_2", "assistant", "第一轮回答");
        memoryService.append("sess_2", "user", "第二轮问题");
        memoryService.append("sess_2", "assistant", "第二轮回答");
        memoryService.append("sess_2", "user", "第三轮问题");
        memoryService.append("sess_2", "assistant", "第三轮回答");

        MemorySnapshot snapshot = memoryService.snapshot("sess_2");

        assertThat(snapshot.summary()).isBlank();
        assertThat(snapshot.recentMessages()).hasSize(4);
        assertThat(snapshot.recentMessages().getFirst().content()).isEqualTo("第二轮问题");
        assertThat(snapshot.recentMessages().getLast().content()).isEqualTo("第三轮回答");
    }

    @Test
    void shouldExtractStableFactsFromUserMessagesIntoSnapshot() {
        MemoryService memoryService = new MemoryService(new MessageSummaryCompressor(4, 6));

        memoryService.append("sess_3", "user", "我叫小智");
        memoryService.append("sess_3", "assistant", "你好，小智");
        memoryService.append("sess_3", "user", "我在杭州做 Java Agent 后端开发");

        MemorySnapshot snapshot = memoryService.snapshot("sess_3");

        assertThat(snapshot.facts()).containsExactly(
                "我叫小智",
                "我在杭州做 Java Agent 后端开发"
        );
    }

    @Test
    void shouldPersistLlmSummaryAndAdvanceWatermarkWhenTriggerReached() {
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        InMemorySummaryStore summaryStore = new InMemorySummaryStore();
        CapturingSummarizer llmSummarizer = CapturingSummarizer.success(markdownSummary("- 用户正在补强会话记忆"));
        MemoryService memoryService = enhancedMemoryService(memoryStore, summaryStore, llmSummarizer);

        appendSixMessages(memoryService, "sess_llm");

        MemorySnapshot snapshot = memoryService.snapshot("sess_llm");

        assertThat(llmSummarizer.calls()).isEqualTo(1);
        assertThat(llmSummarizer.compressedContents()).containsExactly("第一轮问题", "第一轮回答");
        assertThat(snapshot.summary()).contains("### 用户稳定背景");
        assertThat(snapshot.summarySource()).isEqualTo(SummarySource.LLM);
        assertThat(snapshot.recentMessages()).hasSize(4);
        assertThat(summaryStore.get("sess_llm"))
                .hasValueSatisfying(state -> {
                    assertThat(state.summaryMarkdown()).contains("会话记忆");
                    assertThat(state.summarizedMessageCount()).isEqualTo(2);
                    assertThat(state.modelName()).isEqualTo("gpt-5.4-mini");
                });

        MemorySnapshot secondSnapshot = memoryService.snapshot("sess_llm");

        assertThat(llmSummarizer.calls()).isEqualTo(1);
        assertThat(secondSnapshot.summary()).contains("会话记忆");
        assertThat(secondSnapshot.summarySource()).isEqualTo(SummarySource.LLM);
    }

    @Test
    void shouldReuseExistingSummaryBelowTriggerWithoutCallingMini() {
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        InMemorySummaryStore summaryStore = new InMemorySummaryStore();
        summaryStore.save("sess_existing", new ConversationSummaryState(
                markdownSummary("- 已确认使用 mini 模型做增量摘要"),
                4,
                java.time.OffsetDateTime.now(),
                "gpt-5.4-mini",
                120,
                60
        ));
        CapturingSummarizer llmSummarizer = CapturingSummarizer.success(markdownSummary("- 不应该被调用"));
        MemoryService memoryService = enhancedMemoryService(memoryStore, summaryStore, llmSummarizer);

        for (int i = 1; i <= 8; i++) {
            memoryService.append("sess_existing", i % 2 == 0 ? "assistant" : "user", "消息" + i);
        }

        MemorySnapshot snapshot = memoryService.snapshot("sess_existing");

        assertThat(llmSummarizer.calls()).isZero();
        assertThat(snapshot.summary()).contains("已确认使用 mini 模型做增量摘要");
        assertThat(snapshot.summarySource()).isEqualTo(SummarySource.LLM);
        assertThat(snapshot.recentMessages()).extracting(ChatMessageRecord::content)
                .containsExactly("消息5", "消息6", "消息7", "消息8");
    }

    @Test
    void shouldNotCallMiniWhenLockIsUnavailableForLlmSummary() {
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        InMemorySummaryStore summaryStore = new InMemorySummaryStore();
        summaryStore.save("sess_lock", new ConversationSummaryState(
                markdownSummary("- 旧摘要保持可用"),
                0,
                java.time.OffsetDateTime.now(),
                "gpt-5.4-mini",
                20,
                10
        ));
        CapturingSummarizer llmSummarizer = CapturingSummarizer.success(markdownSummary("- 不应该被调用"));
        MemoryService memoryService = enhancedMemoryService(
                memoryStore,
                summaryStore,
                llmSummarizer,
                (sessionId, ttl) -> null
        );

        appendSixMessages(memoryService, "sess_lock");

        MemorySnapshot snapshot = memoryService.snapshot("sess_lock");

        assertThat(llmSummarizer.calls()).isZero();
        assertThat(snapshot.summary()).contains("旧摘要保持可用");
        assertThat(snapshot.summarySource()).isEqualTo(SummarySource.LLM);
        assertThat(snapshot.recentMessages()).hasSize(4);
    }

    @Test
    void shouldFallBackToRuleSummaryWhenMiniFails() {
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        InMemorySummaryStore summaryStore = new InMemorySummaryStore();
        CapturingSummarizer llmSummarizer = CapturingSummarizer.result(
                SummaryResult.error("gpt-5.4-mini", 15, 42, 0, "boom")
        );
        MemoryService memoryService = enhancedMemoryService(memoryStore, summaryStore, llmSummarizer);

        appendSixMessages(memoryService, "sess_error");

        MemorySnapshot snapshot = memoryService.snapshot("sess_error");

        assertThat(llmSummarizer.calls()).isEqualTo(1);
        assertThat(snapshot.summarySource()).isEqualTo(SummarySource.RULE);
        assertThat(snapshot.summary())
                .contains("### 重要上下文")
                .contains("Earlier conversation summary");
        assertThat(summaryStore.get("sess_error")).isEmpty();
    }

    @Test
    void shouldFallBackToRuleSummaryWhenMiniMarkdownIsInvalid() {
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        InMemorySummaryStore summaryStore = new InMemorySummaryStore();
        CapturingSummarizer llmSummarizer = CapturingSummarizer.success("只有一句话，没有固定 Markdown 标题");
        MemoryService memoryService = enhancedMemoryService(memoryStore, summaryStore, llmSummarizer);

        appendSixMessages(memoryService, "sess_invalid");

        MemorySnapshot snapshot = memoryService.snapshot("sess_invalid");

        assertThat(llmSummarizer.calls()).isEqualTo(1);
        assertThat(snapshot.summarySource()).isEqualTo(SummarySource.RULE);
        assertThat(snapshot.summary()).contains("### 用户稳定背景");
        assertThat(summaryStore.get("sess_invalid")).isEmpty();
    }

    @Test
    void shouldRecordTruncatedMiniSummaryAsSuccessOutcome() {
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        InMemorySummaryStore summaryStore = new InMemorySummaryStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CapturingSummarizer llmSummarizer = CapturingSummarizer.result(
                SummaryResult.success(markdownSummary("- 被截断但仍是成功摘要"), "gpt-5.4-mini", 12, 42, 18, true)
        );
        MemoryService memoryService = enhancedMemoryService(
                memoryStore,
                summaryStore,
                llmSummarizer,
                new NoopMemoryLock(),
                new MemoryMetricsRecorder(registry)
        );

        appendSixMessages(memoryService, "sess_truncated");

        MemorySnapshot snapshot = memoryService.snapshot("sess_truncated");

        assertThat(snapshot.summarySource()).isEqualTo(SummarySource.LLM);
        assertThat(registry.find("zhitu_memory_summary_total")
                .tag("outcome", "success")
                .tag("model", "gpt-5.4-mini")
                .counter()).isNotNull();
        assertThat(registry.find("zhitu_memory_summary_total")
                .tag("outcome", "success_truncated")
                .tag("model", "gpt-5.4-mini")
                .counter()).isNull();
    }

    private MemoryService enhancedMemoryService(InMemoryMemoryStore memoryStore,
                                                InMemorySummaryStore summaryStore,
                                                ConversationSummarizer llmSummarizer) {
        return enhancedMemoryService(memoryStore, summaryStore, llmSummarizer, new NoopMemoryLock());
    }

    private MemoryService enhancedMemoryService(InMemoryMemoryStore memoryStore,
                                                InMemorySummaryStore summaryStore,
                                                ConversationSummarizer llmSummarizer,
                                                MemoryLock memoryLock) {
        return enhancedMemoryService(memoryStore, summaryStore, llmSummarizer, memoryLock, MemoryMetricsRecorder.noop());
    }

    private MemoryService enhancedMemoryService(InMemoryMemoryStore memoryStore,
                                                InMemorySummaryStore summaryStore,
                                                ConversationSummarizer llmSummarizer,
                                                MemoryLock memoryLock,
                                                MemoryMetricsRecorder memoryMetricsRecorder) {
        MemorySummaryProperties properties = new MemorySummaryProperties();
        properties.setEnabled(true);
        properties.setTriggerMessageCount(6);
        properties.setMaxRecentMessages(4);
        properties.setFallbackOnFailure(true);
        return new MemoryService(
                memoryStore,
                new MessageSummaryCompressor(4, 6),
                new FactExtractor(),
                memoryLock,
                memoryMetricsRecorder,
                summaryStore,
                llmSummarizer,
                new RuleBasedConversationSummarizer(properties),
                properties
        );
    }

    private void appendSixMessages(MemoryService memoryService, String sessionId) {
        memoryService.append(sessionId, "user", "第一轮问题");
        memoryService.append(sessionId, "assistant", "第一轮回答");
        memoryService.append(sessionId, "user", "第二轮问题");
        memoryService.append(sessionId, "assistant", "第二轮回答");
        memoryService.append(sessionId, "user", "第三轮问题");
        memoryService.append(sessionId, "assistant", "第三轮回答");
    }

    private String markdownSummary(String stableBackgroundLine) {
        return """
                ### 用户稳定背景
                %s

                ### 已确认目标
                - 暂无

                ### 已做决策
                - 暂无

                ### 重要上下文
                - 暂无

                ### 待跟进问题
                - 暂无
                """.formatted(stableBackgroundLine);
    }

    private static class CapturingSummarizer implements ConversationSummarizer {

        private final SummaryResult result;
        private final List<String> compressedContents = new ArrayList<>();
        private int calls;

        private CapturingSummarizer(SummaryResult result) {
            this.result = result;
        }

        static CapturingSummarizer success(String markdown) {
            return result(SummaryResult.success(markdown, "gpt-5.4-mini", 12, 42, 18, false));
        }

        static CapturingSummarizer result(SummaryResult result) {
            return new CapturingSummarizer(result);
        }

        @Override
        public SummaryResult summarize(String previousSummary, List<ChatMessageRecord> messagesToCompress) {
            calls++;
            compressedContents.clear();
            compressedContents.addAll(messagesToCompress.stream().map(ChatMessageRecord::content).toList());
            return result;
        }

        int calls() {
            return calls;
        }

        List<String> compressedContents() {
            return List.copyOf(compressedContents);
        }
    }
}
