package com.zhituagent.memory;

import com.zhituagent.config.MemorySummaryProperties;
import com.zhituagent.context.TokenEstimator;

import java.util.List;

public class RuleBasedConversationSummarizer implements ConversationSummarizer {

    private static final String MODEL_NAME = "rule";

    private final MessageSummaryCompressor compressor;
    private final MemorySummaryProperties properties;
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    public RuleBasedConversationSummarizer(MemorySummaryProperties properties) {
        this.properties = properties;
        this.compressor = new MessageSummaryCompressor(
                Math.max(0, properties.getMaxRecentMessages()),
                Math.max(1, properties.getTriggerMessageCount())
        );
    }

    @Override
    public SummaryResult summarize(String previousSummary, List<ChatMessageRecord> messagesToCompress) {
        long startNanos = System.nanoTime();
        String ruleSummary = compressor.compress(safeList(messagesToCompress)).summary();
        String markdown = toMarkdown(previousSummary, ruleSummary);
        TruncatedText truncated = truncate(markdown, properties.getMaxOutputChars());
        return SummaryResult.success(
                truncated.text(),
                MODEL_NAME,
                elapsedMillis(startNanos),
                inputTokens(previousSummary, messagesToCompress),
                tokenEstimator.estimateText(truncated.text()),
                truncated.truncated()
        );
    }

    public MemorySnapshot fallbackSnapshot(String previousSummary, List<ChatMessageRecord> messages, List<String> facts) {
        SummaryResult result = summarize(previousSummary, messages);
        int splitIndex = Math.max(0, safeList(messages).size() - Math.max(0, properties.getMaxRecentMessages()));
        List<ChatMessageRecord> recent = safeList(messages).subList(splitIndex, safeList(messages).size());
        return new MemorySnapshot(result.summaryMarkdown(), recent, facts, SummarySource.RULE);
    }

    private String toMarkdown(String previousSummary, String ruleSummary) {
        String context = joinNonBlank(previousSummary, ruleSummary);
        String importantContext = context.isBlank() ? "- 暂无" : "- " + context;
        return """
                ### 用户稳定背景
                - 暂无

                ### 已确认目标
                - 暂无

                ### 已做决策
                - 暂无

                ### 重要上下文
                %s

                ### 待跟进问题
                - 暂无
                """.formatted(importantContext).trim();
    }

    private String joinNonBlank(String previousSummary, String ruleSummary) {
        if (previousSummary != null && !previousSummary.isBlank() && ruleSummary != null && !ruleSummary.isBlank()) {
            return previousSummary.trim() + "\n" + ruleSummary.trim();
        }
        if (previousSummary != null && !previousSummary.isBlank()) {
            return previousSummary.trim();
        }
        return ruleSummary == null ? "" : ruleSummary.trim();
    }

    private TruncatedText truncate(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return new TruncatedText(text, false);
        }
        return new TruncatedText(text.substring(0, maxChars).trim(), true);
    }

    private long inputTokens(String previousSummary, List<ChatMessageRecord> messages) {
        long total = tokenEstimator.estimateText(previousSummary);
        for (ChatMessageRecord message : safeList(messages)) {
            total += tokenEstimator.estimateText(message.content());
        }
        return total;
    }

    private List<ChatMessageRecord> safeList(List<ChatMessageRecord> messages) {
        return messages == null ? List.of() : messages;
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record TruncatedText(String text, boolean truncated) {
    }
}
