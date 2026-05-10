package com.zhituagent.context;

import com.zhituagent.config.ContextProperties;
import com.zhituagent.memory.MemorySnapshot;
import com.zhituagent.memory.SummarySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ContextManager {

    private static final int DEFAULT_MAX_INPUT_TOKENS = 1024;
    private static final int DEFAULT_MAX_SUMMARY_TOKENS = 180;
    private static final int DEFAULT_MAX_FACTS_TOKENS = 120;
    private static final int DEFAULT_MAX_EVIDENCE_TOKENS = 240;
    private static final int DEFAULT_MAX_MESSAGE_TOKENS = 120;
    private static final int DEFAULT_MIN_KEEP_RECENT_MESSAGES = 2;
    private static final String BASE_STRATEGY = "recent-summary";
    private static final String LLM_SUMMARY_SUFFIX = "-llm-summary";
    private static final String RULE_SUMMARY_SUFFIX = "-rule-summary";
    private static final String FACTS_SUFFIX = "-facts";
    private static final String BUDGETED_SUFFIX = "-budgeted";
    private static final String OVERFLOW_SUFFIX = "-overflow";

    private final TokenEstimator tokenEstimator;
    private final int maxInputTokens;
    private final int maxSummaryTokens;
    private final int maxFactsTokens;
    private final int maxEvidenceTokens;
    private final int maxMessageTokens;
    private final int minKeepRecentMessages;

    public ContextManager() {
        this(
                new TokenEstimator(),
                DEFAULT_MAX_INPUT_TOKENS,
                DEFAULT_MAX_SUMMARY_TOKENS,
                DEFAULT_MAX_FACTS_TOKENS,
                DEFAULT_MAX_EVIDENCE_TOKENS,
                DEFAULT_MAX_MESSAGE_TOKENS,
                DEFAULT_MIN_KEEP_RECENT_MESSAGES
        );
    }

    @Autowired
    public ContextManager(ContextProperties contextProperties) {
        this(
                new TokenEstimator(),
                contextProperties.getMaxInputTokens(),
                contextProperties.getMaxSummaryTokens(),
                contextProperties.getMaxFactsTokens(),
                contextProperties.getMaxEvidenceTokens(),
                contextProperties.getMaxMessageTokens(),
                contextProperties.getMinKeepRecentMessages()
        );
    }

    ContextManager(int maxInputTokens,
                   int maxSummaryTokens,
                   int maxFactsTokens,
                   int maxEvidenceTokens,
                   int maxMessageTokens) {
        this(
                new TokenEstimator(),
                maxInputTokens,
                maxSummaryTokens,
                maxFactsTokens,
                maxEvidenceTokens,
                maxMessageTokens,
                DEFAULT_MIN_KEEP_RECENT_MESSAGES
        );
    }

    ContextManager(int maxInputTokens,
                   int maxSummaryTokens,
                   int maxFactsTokens,
                   int maxEvidenceTokens,
                   int maxMessageTokens,
                   int minKeepRecentMessages) {
        this(
                new TokenEstimator(),
                maxInputTokens,
                maxSummaryTokens,
                maxFactsTokens,
                maxEvidenceTokens,
                maxMessageTokens,
                minKeepRecentMessages
        );
    }

    ContextManager(TokenEstimator tokenEstimator,
                   int maxInputTokens,
                   int maxSummaryTokens,
                   int maxFactsTokens,
                   int maxEvidenceTokens,
                   int maxMessageTokens,
                   int minKeepRecentMessages) {
        this.tokenEstimator = tokenEstimator;
        this.maxInputTokens = maxInputTokens;
        this.maxSummaryTokens = maxSummaryTokens;
        this.maxFactsTokens = maxFactsTokens;
        this.maxEvidenceTokens = maxEvidenceTokens;
        this.maxMessageTokens = maxMessageTokens;
        this.minKeepRecentMessages = Math.max(0, minKeepRecentMessages);
    }

    public ContextBundle build(String systemPrompt,
                               MemorySnapshot memorySnapshot,
                               String currentMessage,
                               String ragEvidence) {
        BudgetedContext budgetedContext = budgetContext(systemPrompt, memorySnapshot, currentMessage, ragEvidence);
        List<String> modelMessages = buildModelMessages(
                systemPrompt,
                budgetedContext.summary(),
                budgetedContext.facts(),
                budgetedContext.recentMessages(),
                budgetedContext.ragEvidence(),
                currentMessage
        );

        return new ContextBundle(
                systemPrompt,
                budgetedContext.summary(),
                budgetedContext.recentMessages(),
                budgetedContext.facts(),
                currentMessage,
                List.copyOf(modelMessages),
                budgetedContext.contextStrategy()
        );
    }

    /**
     * Estimates the upstream "naive concat" input token count — what would be sent
     * to the LLM if no trimming were applied (system prompt + summary + all facts
     * + all recent messages + evidence + current message). Used by metrics callers
     * to compute raw vs budgeted reduction ratios.
     */
    public long estimateRawTokens(String systemPrompt,
                                  MemorySnapshot memorySnapshot,
                                  String currentMessage,
                                  String ragEvidence) {
        long total = tokenEstimator.estimateText(systemPrompt);
        if (memorySnapshot != null) {
            total += tokenEstimator.estimateText(memorySnapshot.summary());
            for (String fact : safeList(memorySnapshot.facts())) {
                total += tokenEstimator.estimateText(fact);
            }
            for (com.zhituagent.memory.ChatMessageRecord msg : safeList(memorySnapshot.recentMessages())) {
                total += tokenEstimator.estimateText(msg.content());
            }
        }
        total += tokenEstimator.estimateText(ragEvidence);
        total += tokenEstimator.estimateText(currentMessage);
        return total;
    }

    /**
     * Estimates the budgeted input token count for the post-trim model messages,
     * exposing the package-private TokenEstimator so callers without DI access
     * don't have to instantiate their own.
     */
    public long estimateMessages(List<String> modelMessages) {
        return tokenEstimator.estimateMessages(modelMessages);
    }

    private BudgetedContext budgetContext(String systemPrompt,
                                          MemorySnapshot memorySnapshot,
                                          String currentMessage,
                                          String ragEvidence) {
        String summary = trimToTokenLimit(memorySnapshot.summary(), maxSummaryTokens);
        String evidence = trimToTokenLimit(ragEvidence, maxEvidenceTokens);
        List<String> facts = limitFacts(memorySnapshot.facts());
        List<com.zhituagent.memory.ChatMessageRecord> recentMessages = limitRecentMessages(memorySnapshot.recentMessages());

        boolean budgeted = !safeEquals(summary, memorySnapshot.summary())
                || !safeEquals(evidence, ragEvidence)
                || facts.size() != safeList(memorySnapshot.facts()).size()
                || recentMessages.size() != safeList(memorySnapshot.recentMessages()).size();

        List<String> modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);

        // 四级渐进裁剪：按"降级代价从低到高"排序，优先丢弃信息密度最低的内容。
        // Tier 1: 丢弃最旧的对话轮次，保留最近 N 轮（默认 2）保证对话连贯性
        // Tier 2: 丢弃最旧的用户事实，保留 ≥1 条
        // Tier 3: 完全丢弃历史摘要
        // Tier 4: 对 RAG 证据减半截断（最后一道防线）
        while (tokenEstimator.estimateMessages(modelMessages) > maxInputTokens
                && recentMessages.size() > minKeepRecentMessages) {
            recentMessages = new ArrayList<>(recentMessages.subList(1, recentMessages.size()));
            budgeted = true;
            modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);
        }

        // Tier 2: drop oldest facts (keep at least 1).
        while (tokenEstimator.estimateMessages(modelMessages) > maxInputTokens && facts.size() > 1) {
            facts = new ArrayList<>(facts.subList(1, facts.size()));
            budgeted = true;
            modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);
        }

        // Tier 3: drop summary entirely.
        if (tokenEstimator.estimateMessages(modelMessages) > maxInputTokens && summary != null && !summary.isBlank()) {
            summary = "";
            budgeted = true;
            modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);
        }

        // Tier 4: halve evidence.
        if (tokenEstimator.estimateMessages(modelMessages) > maxInputTokens && evidence != null && !evidence.isBlank()) {
            evidence = trimToTokenLimit(evidence, Math.max(24, maxEvidenceTokens / 2));
            budgeted = true;
            modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);
        }

        // Tier 5: keep the minimum recent-message count, but shrink their
        // content further so the floor remains conversational without forcing
        // an overflow stamp on otherwise recoverable cases.
        int recentTokenLimit = Math.max(12, maxMessageTokens / 2);
        while (tokenEstimator.estimateMessages(modelMessages) > maxInputTokens
                && recentTokenLimit >= 12
                && canShrinkRecentMessages(recentMessages, recentTokenLimit)) {
            recentMessages = shrinkRecentMessages(recentMessages, recentTokenLimit);
            budgeted = true;
            modelMessages = buildModelMessages(systemPrompt, summary, facts, recentMessages, evidence, currentMessage);
            recentTokenLimit = recentTokenLimit / 2;
        }

        // After all four tiers: stamp -overflow if still over budget so the floor
        // is observable (e.g. system prompt + minKeepRecent + 1 fact + halved
        // evidence + current msg already exceeds maxInputTokens).
        boolean overflow = tokenEstimator.estimateMessages(modelMessages) > maxInputTokens;

        return new BudgetedContext(
                summary,
                List.copyOf(recentMessages),
                List.copyOf(facts),
                evidence,
                resolveContextStrategy(memorySnapshot.summarySource(), facts, budgeted, overflow)
        );
    }

    private List<String> buildModelMessages(String systemPrompt,
                                            String summary,
                                            List<String> facts,
                                            List<com.zhituagent.memory.ChatMessageRecord> recentMessages,
                                            String ragEvidence,
                                            String currentMessage) {
        List<String> modelMessages = new ArrayList<>();
        modelMessages.add("SYSTEM: " + systemPrompt);

        if (summary != null && !summary.isBlank()) {
            modelMessages.add("SUMMARY: " + summary);
        }

        if (facts != null && !facts.isEmpty()) {
            modelMessages.add("FACTS: " + String.join(" | ", facts));
        }

        safeList(recentMessages).forEach(message ->
                modelMessages.add(message.role().toUpperCase() + ": " + message.content())
        );

        if (ragEvidence != null && !ragEvidence.isBlank()) {
            modelMessages.add("EVIDENCE: " + ragEvidence);
        }

        modelMessages.add("USER: " + currentMessage);
        return modelMessages;
    }

    private List<String> limitFacts(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }

        List<String> limitedFacts = new ArrayList<>();
        long usedTokens = 0;
        for (String fact : facts) {
            String normalizedFact = trimToTokenLimit(fact, Math.min(maxMessageTokens, maxFactsTokens));
            if (normalizedFact.isBlank()) {
                continue;
            }

            long factTokens = tokenEstimator.estimateText(normalizedFact);
            if (!limitedFacts.isEmpty() && usedTokens + factTokens > maxFactsTokens) {
                break;
            }

            limitedFacts.add(normalizedFact);
            usedTokens += factTokens;
        }
        return List.copyOf(limitedFacts);
    }

    private List<com.zhituagent.memory.ChatMessageRecord> limitRecentMessages(List<com.zhituagent.memory.ChatMessageRecord> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return List.of();
        }
        List<com.zhituagent.memory.ChatMessageRecord> limited = new ArrayList<>();
        for (com.zhituagent.memory.ChatMessageRecord message : recentMessages) {
            String normalizedContent = trimToTokenLimit(message.content(), maxMessageTokens);
            limited.add(new com.zhituagent.memory.ChatMessageRecord(message.role(), normalizedContent, message.timestamp()));
        }
        return List.copyOf(limited);
    }

    private boolean canShrinkRecentMessages(List<com.zhituagent.memory.ChatMessageRecord> recentMessages, int tokenLimit) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return false;
        }
        return recentMessages.stream()
                .anyMatch(message -> tokenEstimator.estimateText(message.content()) > tokenLimit);
    }

    private List<com.zhituagent.memory.ChatMessageRecord> shrinkRecentMessages(List<com.zhituagent.memory.ChatMessageRecord> recentMessages,
                                                                               int tokenLimit) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return List.of();
        }
        List<com.zhituagent.memory.ChatMessageRecord> limited = new ArrayList<>();
        for (com.zhituagent.memory.ChatMessageRecord message : recentMessages) {
            limited.add(new com.zhituagent.memory.ChatMessageRecord(
                    message.role(),
                    trimToTokenLimit(message.content(), tokenLimit),
                    message.timestamp()
            ));
        }
        return List.copyOf(limited);
    }

    private String trimToTokenLimit(String text, int tokenLimit) {
        if (text == null || text.isBlank() || tokenLimit <= 0) {
            return "";
        }
        if (tokenEstimator.estimateText(text) <= tokenLimit) {
            return text;
        }

        String trimmed = text.trim();
        int low = 0;
        int high = trimmed.length();
        String best = "";
        while (low <= high) {
            int mid = (low + high) / 2;
            String candidate = trimmed.substring(0, mid).trim();
            if (!candidate.isEmpty()) {
                candidate = candidate + "...";
            }
            long estimatedTokens = tokenEstimator.estimateText(candidate);
            if (estimatedTokens <= tokenLimit) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    private String resolveContextStrategy(SummarySource summarySource, List<String> facts, boolean budgeted, boolean overflow) {
        String strategy = BASE_STRATEGY;
        if (summarySource == SummarySource.LLM) {
            strategy += LLM_SUMMARY_SUFFIX;
        } else if (summarySource == SummarySource.RULE) {
            strategy += RULE_SUMMARY_SUFFIX;
        }
        if (facts != null && !facts.isEmpty()) {
            strategy += FACTS_SUFFIX;
        }
        if (budgeted) {
            strategy += BUDGETED_SUFFIX;
        }
        if (overflow) {
            strategy += OVERFLOW_SUFFIX;
        }
        return strategy;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private boolean safeEquals(String left, String right) {
        return (left == null || left.isBlank())
                ? right == null || right.isBlank()
                : left.equals(right);
    }

    private record BudgetedContext(
            String summary,
            List<com.zhituagent.memory.ChatMessageRecord> recentMessages,
            List<String> facts,
            String ragEvidence,
            String contextStrategy
    ) {
    }
}
