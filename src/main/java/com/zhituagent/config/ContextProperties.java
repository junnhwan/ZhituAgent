package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Context window budget configuration for {@code ContextManager}.
 *
 * <p>Default {@code maxInputTokens=1024} reflects the trade-off that the prod
 * system prompt ({@code chat-agent.txt}) already costs ~850 CJK tokens, so a
 * 640-token budget (the historic hard-coded default) caused every conversation
 * to trip the four-tier trimmer and stamp every {@code contextStrategy} with
 * the {@code -budgeted} suffix — making the trace signal useless. 1024 leaves
 * ~170 tokens of headroom for short-history cases while still triggering the
 * budget loop on long contexts (see {@code context-budget-001} fixture case).
 *
 * <p>Override via {@code zhitu.context.max-input-tokens=...}; ablation
 * benchmarks set this to {@code Integer.MAX_VALUE / 2} (~1B) to run a
 * disabled-budget control group against the default-on group.
 */
@ConfigurationProperties(prefix = "zhitu.context")
public class ContextProperties {

    private int maxInputTokens = 1024;
    private int maxSummaryTokens = 180;
    private int maxFactsTokens = 120;
    private int maxEvidenceTokens = 240;
    private int maxMessageTokens = 120;

    public int getMaxInputTokens() {
        return maxInputTokens;
    }

    public void setMaxInputTokens(int maxInputTokens) {
        this.maxInputTokens = maxInputTokens;
    }

    public int getMaxSummaryTokens() {
        return maxSummaryTokens;
    }

    public void setMaxSummaryTokens(int maxSummaryTokens) {
        this.maxSummaryTokens = maxSummaryTokens;
    }

    public int getMaxFactsTokens() {
        return maxFactsTokens;
    }

    public void setMaxFactsTokens(int maxFactsTokens) {
        this.maxFactsTokens = maxFactsTokens;
    }

    public int getMaxEvidenceTokens() {
        return maxEvidenceTokens;
    }

    public void setMaxEvidenceTokens(int maxEvidenceTokens) {
        this.maxEvidenceTokens = maxEvidenceTokens;
    }

    public int getMaxMessageTokens() {
        return maxMessageTokens;
    }

    public void setMaxMessageTokens(int maxMessageTokens) {
        this.maxMessageTokens = maxMessageTokens;
    }
}
