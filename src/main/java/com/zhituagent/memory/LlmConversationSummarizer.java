package com.zhituagent.memory;

import com.zhituagent.config.MemorySummaryProperties;
import com.zhituagent.context.TokenEstimator;
import com.zhituagent.llm.LlmRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class LlmConversationSummarizer implements ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(LlmConversationSummarizer.class);

    private static final String SYSTEM_PROMPT = """
            你负责把一段会话历史压缩成后续回答可复用的中文 Markdown 摘要。
            只能基于输入里的旧摘要和待压缩消息，不允许补充输入中没有出现的事实、
            不允许编造工具结果，不允许编造 RAG 证据。

            只保留对后续回答有用的信息：用户背景、目标、已确认决策、未解决问题、
            重要约束。如果输入里只有寒暄或无长期价值内容，对应段落写 `暂无`。

            必须输出下面五个标题，标题文字和顺序保持不变：
            ### 用户稳定背景
            ### 已确认目标
            ### 已做决策
            ### 重要上下文
            ### 待跟进问题
            """;

    private final LlmRuntime llmRuntime;
    private final String modelName;
    private final MemorySummaryProperties properties;
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    public LlmConversationSummarizer(LlmRuntime llmRuntime,
                                     String modelName,
                                     MemorySummaryProperties properties) {
        this.llmRuntime = llmRuntime;
        this.modelName = modelName == null || modelName.isBlank() ? "unknown" : modelName;
        this.properties = properties;
    }

    @Override
    public SummaryResult summarize(String previousSummary, List<ChatMessageRecord> messagesToCompress) {
        if (llmRuntime == null) {
            return SummaryResult.disabled();
        }
        List<String> messages = List.of("USER: " + userPrompt(previousSummary, messagesToCompress));
        long inputTokens = tokenEstimator.estimateText(SYSTEM_PROMPT) + tokenEstimator.estimateMessages(messages);
        long startNanos = System.nanoTime();
        try {
            String raw = CompletableFuture.supplyAsync(() -> llmRuntime.generate(
                            SYSTEM_PROMPT,
                            messages,
                            Map.of("purpose", properties.getModelPurpose(), "phase", properties.getModelPurpose())
                    ))
                    .orTimeout(Math.max(1, properties.getTimeoutMillis()), TimeUnit.MILLISECONDS)
                    .get();
            String normalized = raw == null ? "" : raw.trim();
            TruncatedText truncated = truncate(normalized, properties.getMaxOutputChars());
            return SummaryResult.success(
                    truncated.text(),
                    modelName,
                    elapsedMillis(startNanos),
                    inputTokens,
                    tokenEstimator.estimateText(truncated.text()),
                    truncated.truncated()
            );
        } catch (Exception exception) {
            long latencyMs = elapsedMillis(startNanos);
            if (isTimeout(exception)) {
                log.warn("memory summary timed out model={} latencyMs={}", modelName, latencyMs);
                return SummaryResult.timeout(modelName, latencyMs, inputTokens);
            }
            log.warn("memory summary failed model={} latencyMs={} error={}", modelName, latencyMs, exception.getMessage());
            return SummaryResult.error(modelName, latencyMs, inputTokens, 0, exception.getMessage());
        }
    }

    private String userPrompt(String previousSummary, List<ChatMessageRecord> messagesToCompress) {
        StringBuilder builder = new StringBuilder();
        builder.append("旧摘要:\n");
        builder.append(previousSummary == null || previousSummary.isBlank() ? "暂无" : previousSummary.trim());
        builder.append("\n\n待压缩消息:\n");
        if (messagesToCompress == null || messagesToCompress.isEmpty()) {
            builder.append("暂无");
        } else {
            for (ChatMessageRecord message : messagesToCompress) {
                builder.append("- ")
                        .append(message.role() == null ? "unknown" : message.role())
                        .append(": ")
                        .append(message.content() == null ? "" : message.content())
                        .append('\n');
            }
        }
        builder.append("\n\n请输出固定 Markdown 摘要。");
        return builder.toString();
    }

    private boolean isTimeout(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private TruncatedText truncate(String text, int maxChars) {
        if (text == null) {
            return new TruncatedText("", false);
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return new TruncatedText(text, false);
        }
        return new TruncatedText(text.substring(0, maxChars).trim(), true);
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record TruncatedText(String text, boolean truncated) {
    }
}
