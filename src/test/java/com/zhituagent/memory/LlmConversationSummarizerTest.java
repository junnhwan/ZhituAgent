package com.zhituagent.memory;

import com.zhituagent.config.MemorySummaryProperties;
import com.zhituagent.llm.LlmRuntime;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConversationSummarizerTest {

    @Test
    void shouldCallRuntimeWithMemorySummaryMetadataAndReturnMarkdown() {
        MemorySummaryProperties properties = new MemorySummaryProperties();
        properties.setEnabled(true);
        properties.setTimeoutMillis(1000);
        properties.setMaxOutputChars(1200);
        properties.setModelPurpose("memory-summary");
        CapturingRuntime runtime = new CapturingRuntime(markdownSummary());
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(runtime, "gpt-5.4-mini", properties);

        SummaryResult result = summarizer.summarize(
                "旧摘要",
                List.of(new ChatMessageRecord("user", "用户说要优化上下文压缩", OffsetDateTime.now()))
        );

        assertThat(result.outcome()).isEqualTo(SummaryOutcome.SUCCESS);
        assertThat(result.summaryMarkdown()).contains("### 用户稳定背景");
        assertThat(result.modelName()).isEqualTo("gpt-5.4-mini");
        assertThat(result.inputTokenEstimate()).isPositive();
        assertThat(result.outputTokenEstimate()).isPositive();
        assertThat(runtime.metadata()).containsEntry("purpose", "memory-summary");
        assertThat(runtime.messages()).anyMatch(message -> message.contains("旧摘要"));
        assertThat(runtime.messages()).anyMatch(message -> message.contains("用户说要优化上下文压缩"));
    }

    @Test
    void shouldTimeoutInsteadOfThrowingWhenRuntimeIsTooSlow() {
        MemorySummaryProperties properties = new MemorySummaryProperties();
        properties.setEnabled(true);
        properties.setTimeoutMillis(10);
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(
                new SlowRuntime(),
                "gpt-5.4-mini",
                properties
        );

        SummaryResult result = summarizer.summarize("", List.of(
                new ChatMessageRecord("user", "需要触发超时", OffsetDateTime.now())
        ));

        assertThat(result.outcome()).isEqualTo(SummaryOutcome.TIMEOUT);
        assertThat(result.modelName()).isEqualTo("gpt-5.4-mini");
    }

    private String markdownSummary() {
        return """
                ### 用户稳定背景
                - 用户关注会话记忆

                ### 已确认目标
                - 用 mini 模型做摘要

                ### 已做决策
                - 默认关闭

                ### 重要上下文
                - 保留 recent messages

                ### 待跟进问题
                - 暂无
                """;
    }

    private static class CapturingRuntime implements LlmRuntime {

        private final String response;
        private List<String> messages = List.of();
        private Map<String, Object> metadata = Map.of();

        private CapturingRuntime(String response) {
            this.response = response;
        }

        @Override
        public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
            this.messages = List.copyOf(messages);
            this.metadata = Map.copyOf(metadata);
            return response;
        }

        @Override
        public void stream(String systemPrompt, List<String> messages, Map<String, Object> metadata, java.util.function.Consumer<String> onToken, Runnable onComplete) {
            throw new UnsupportedOperationException();
        }

        List<String> messages() {
            return messages;
        }

        Map<String, Object> metadata() {
            return metadata;
        }
    }

    private static class SlowRuntime implements LlmRuntime {

        @Override
        public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return "";
        }

        @Override
        public void stream(String systemPrompt, List<String> messages, Map<String, Object> metadata, java.util.function.Consumer<String> onToken, Runnable onComplete) {
            throw new UnsupportedOperationException();
        }
    }
}
