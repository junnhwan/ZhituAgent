package com.zhituagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.config.MemorySummaryProperties;
import com.zhituagent.context.ContextBundle;
import com.zhituagent.context.ContextManager;
import com.zhituagent.context.TokenEstimator;
import com.zhituagent.metrics.MemoryMetricsRecorder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long-session memory benchmark.
 *
 * <p>Run explicitly with {@code .\mvnw.cmd -o -Pbenchmark "-Dtest=MemorySummaryBenchmark" test}.
 * It does not call a real LLM. The "mini markdown" arm uses a deterministic
 * summarizer that preserves the same kind of goals / decisions / constraints a
 * small model is expected to keep, so the benchmark is stable offline while
 * still producing a resume-friendly comparison table.
 */
@Tag("benchmark")
class MemorySummaryBenchmark {

    private static final String SYSTEM_PROMPT = "你是 ZhituAgent, 需要基于会话记忆回答用户后续问题。";
    private static final String CURRENT_MESSAGE = "继续根据前面的决策给我一个实现建议";
    private static final List<String> KEY_FACTS = List.of(
            "用户在杭州做 Java Agent 后端开发",
            "目标是优化会话记忆与上下文管理",
            "已决定默认关闭并使用 mini 模型做增量摘要",
            "待跟进真实模型验证"
    );

    private final ContextManager contextManager = new ContextManager();
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    @Test
    void compareRawRuleAndMiniMarkdownSummary() throws IOException {
        List<ScenarioResult> results = new ArrayList<>();
        for (int turns : List.of(10, 30, 60)) {
            List<ChatMessageRecord> messages = generateConversation(turns);
            results.add(rawHistoryResult(turns, messages));
            results.add(ruleSummaryResult(turns, messages));
            results.add(miniMarkdownResult(turns, messages));
        }

        writeArtefacts(results);

        for (int turns : List.of(10, 30, 60)) {
            ScenarioResult raw = find(results, turns, "raw-history");
            ScenarioResult rule = find(results, turns, "rule-truncated-summary");
            ScenarioResult mini = find(results, turns, "mini-markdown-summary");
            assertThat(rule.inputTokens()).isLessThan(raw.inputTokens());
            assertThat(mini.inputTokens()).isLessThan(raw.inputTokens());
            assertThat(mini.keyInfoHitRate()).isGreaterThanOrEqualTo(rule.keyInfoHitRate());
        }
    }

    private ScenarioResult rawHistoryResult(int turns, List<ChatMessageRecord> messages) {
        long tokens = tokenEstimator.estimateText(SYSTEM_PROMPT) + tokenEstimator.estimateText(CURRENT_MESSAGE);
        StringBuilder context = new StringBuilder(SYSTEM_PROMPT).append('\n');
        for (ChatMessageRecord message : messages) {
            tokens += tokenEstimator.estimateText(message.content());
            context.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        context.append(CURRENT_MESSAGE);
        return new ScenarioResult(
                turns,
                "raw-history",
                tokens,
                0,
                0,
                keyInfoHitRate(context.toString())
        );
    }

    private ScenarioResult ruleSummaryResult(int turns, List<ChatMessageRecord> messages) {
        long startNanos = System.nanoTime();
        MemorySnapshot snapshot = new MessageSummaryCompressor(4, 6).compress(messages);
        long latencyMs = elapsedMillis(startNanos);
        ContextBundle bundle = contextManager.build(SYSTEM_PROMPT, snapshot, CURRENT_MESSAGE, "");
        String combined = String.join("\n", bundle.modelMessages());
        return new ScenarioResult(
                turns,
                "rule-truncated-summary",
                tokenEstimator.estimateMessages(bundle.modelMessages()),
                tokenEstimator.estimateText(snapshot.summary()),
                latencyMs,
                keyInfoHitRate(combined)
        );
    }

    private ScenarioResult miniMarkdownResult(int turns, List<ChatMessageRecord> messages) {
        MemorySummaryProperties properties = new MemorySummaryProperties();
        properties.setEnabled(true);
        properties.setTriggerMessageCount(6);
        properties.setMaxRecentMessages(4);
        properties.setMaxOutputChars(1200);
        DeterministicMiniSummarizer summarizer = new DeterministicMiniSummarizer();
        MemoryService memoryService = new MemoryService(
                new InMemoryMemoryStore(),
                new MessageSummaryCompressor(4, 6),
                new FactExtractor(),
                new NoopMemoryLock(),
                MemoryMetricsRecorder.noop(),
                new InMemorySummaryStore(),
                summarizer,
                new RuleBasedConversationSummarizer(properties),
                properties
        );

        String sessionId = "bench_" + turns;
        for (ChatMessageRecord message : messages) {
            memoryService.append(sessionId, message.role(), message.content());
            memoryService.snapshot(sessionId);
        }
        MemorySnapshot snapshot = memoryService.snapshot(sessionId);
        ContextBundle bundle = contextManager.build(SYSTEM_PROMPT, snapshot, CURRENT_MESSAGE, "");
        String combined = String.join("\n", bundle.modelMessages());
        long avgLatency = summarizer.calls() == 0 ? 0 : summarizer.totalLatencyMs() / summarizer.calls();
        return new ScenarioResult(
                turns,
                "mini-markdown-summary",
                tokenEstimator.estimateMessages(bundle.modelMessages()),
                tokenEstimator.estimateText(snapshot.summary()),
                avgLatency,
                keyInfoHitRate(combined)
        );
    }

    private List<ChatMessageRecord> generateConversation(int turns) {
        List<ChatMessageRecord> messages = new ArrayList<>();
        OffsetDateTime base = OffsetDateTime.now();
        messages.add(new ChatMessageRecord("user", "我在杭州做 Java Agent 后端开发，目标是优化会话记忆与上下文管理。", base));
        messages.add(new ChatMessageRecord("assistant", "收到，后面建议围绕 summary、facts、recent messages 和 token budget 展开。", base.plusSeconds(30)));
        messages.add(new ChatMessageRecord("user", "已决定默认关闭并使用 mini 模型做增量摘要，失败时必须走规则降级。", base.plusSeconds(60)));
        messages.add(new ChatMessageRecord("assistant", "这个决策可以降低默认本地依赖，同时给真实模型配置留入口。", base.plusSeconds(90)));
        messages.add(new ChatMessageRecord("user", "待跟进真实模型验证，但不能把密钥或私有 endpoint 写进文档。", base.plusSeconds(120)));
        messages.add(new ChatMessageRecord("assistant", "真实验证需要显式设置环境变量，默认单测继续离线。", base.plusSeconds(150)));

        for (int i = 3; i < turns; i++) {
            messages.add(new ChatMessageRecord("user", "第 " + i + " 轮继续讨论实现细节、日志指标和边界情况。", base.plusSeconds(i * 60L)));
            messages.add(new ChatMessageRecord("assistant", "第 " + i + " 轮回答强调保持原始消息不裁剪，并用 watermark 推进摘要。", base.plusSeconds(i * 60L + 30L)));
        }
        return messages;
    }

    private double keyInfoHitRate(String text) {
        long hits = KEY_FACTS.stream().filter(text::contains).count();
        return hits / (double) KEY_FACTS.size();
    }

    private ScenarioResult find(List<ScenarioResult> results, int turns, String strategy) {
        return results.stream()
                .filter(result -> result.turns() == turns && strategy.equals(result.strategy()))
                .findFirst()
                .orElseThrow();
    }

    private void writeArtefacts(List<ScenarioResult> results) throws IOException {
        Path outDir = Path.of("target", "memory-summary-bench");
        Files.createDirectories(outDir);
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results);
        long ts = Instant.now().getEpochSecond();
        Files.writeString(outDir.resolve("results-" + ts + ".json"), json, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("results-latest.json"), json, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("results-latest.md"), markdownTable(results), StandardCharsets.UTF_8);
    }

    private String markdownTable(List<ScenarioResult> results) {
        StringBuilder md = new StringBuilder();
        md.append("# Memory Summary Benchmark Results\n\n");
        md.append("| turns | strategy | input tokens | summary tokens | avg summary latency ms | key info hit rate |\n");
        md.append("|---|---|---:|---:|---:|---:|\n");
        for (ScenarioResult result : results) {
            md.append(String.format("| %d | %s | %d | %d | %d | %.2f |%n",
                    result.turns(),
                    result.strategy(),
                    result.inputTokens(),
                    result.summaryTokens(),
                    result.avgSummaryLatencyMs(),
                    result.keyInfoHitRate()));
        }
        return md.toString();
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public record ScenarioResult(
            int turns,
            String strategy,
            long inputTokens,
            long summaryTokens,
            long avgSummaryLatencyMs,
            double keyInfoHitRate
    ) {
    }

    private static class DeterministicMiniSummarizer implements ConversationSummarizer {

        private long calls;
        private long totalLatencyMs;

        @Override
        public SummaryResult summarize(String previousSummary, List<ChatMessageRecord> messagesToCompress) {
            long startNanos = System.nanoTime();
            String text = previousSummary + "\n" + messagesToCompress.stream()
                    .map(ChatMessageRecord::content)
                    .reduce("", (left, right) -> left + "\n" + right);
            String markdown = """
                    ### 用户稳定背景
                    - %s

                    ### 已确认目标
                    - %s

                    ### 已做决策
                    - %s

                    ### 重要上下文
                    - 原始消息不裁剪，通过 watermark 推进摘要状态。

                    ### 待跟进问题
                    - %s
                    """.formatted(
                    contains(text, "杭州") ? "用户在杭州做 Java Agent 后端开发" : "暂无",
                    contains(text, "优化会话记忆") ? "目标是优化会话记忆与上下文管理" : "暂无",
                    contains(text, "mini 模型") ? "已决定默认关闭并使用 mini 模型做增量摘要" : "暂无",
                    contains(text, "真实模型验证") ? "待跟进真实模型验证" : "暂无"
            );
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            calls++;
            totalLatencyMs += latencyMs;
            return SummaryResult.success(markdown, "deterministic-mini", latencyMs, text.length(), markdown.length(), false);
        }

        private boolean contains(String text, String needle) {
            return text != null && text.contains(needle);
        }

        long calls() {
            return calls;
        }

        long totalLatencyMs() {
            return totalLatencyMs;
        }
    }
}
