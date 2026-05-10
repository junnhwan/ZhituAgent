package com.zhituagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MemoryMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public MemoryMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public static MemoryMetricsRecorder noop() {
        return new MemoryMetricsRecorder(null);
    }

    public void recordCompression(String outcome, String store) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("zhitu_memory_compression_total")
                .tag("outcome", safe(outcome))
                .tag("store", safe(store))
                .register(meterRegistry)
                .increment();
    }

    public void recordSummary(String outcome,
                              String model,
                              long latencyMs,
                              long inputTokens,
                              long outputTokens) {
        if (meterRegistry == null) {
            return;
        }
        String modelTag = safe(model);
        Counter.builder("zhitu_memory_summary_total")
                .tag("outcome", safe(outcome))
                .tag("model", modelTag)
                .register(meterRegistry)
                .increment();
        DistributionSummary.builder("zhitu_memory_summary_latency_ms")
                .tag("model", modelTag)
                .baseUnit("milliseconds")
                .register(meterRegistry)
                .record(Math.max(0, latencyMs));
        DistributionSummary.builder("zhitu_memory_summary_tokens")
                .tag("phase", "input")
                .tag("model", modelTag)
                .baseUnit("tokens")
                .register(meterRegistry)
                .record(Math.max(0, inputTokens));
        DistributionSummary.builder("zhitu_memory_summary_tokens")
                .tag("phase", "output")
                .tag("model", modelTag)
                .baseUnit("tokens")
                .register(meterRegistry)
                .record(Math.max(0, outputTokens));
    }

    /**
     * Records the input-token shape produced by ContextManager for a single LLM call.
     *
     * <p>Two distribution summaries — phase=raw (system + facts + full history +
     * evidence + current, naive concat) and phase=budgeted (after four-tier
     * trimming) — let Grafana plot reduction ratios per strategy. A counter
     * tracks cumulative saved tokens for high-level cost-savings dashboards.
     *
     * @param rawTokens       upstream input token estimate before trimming
     * @param budgetedTokens  input token estimate after ContextManager.build()
     * @param strategy        contextStrategy stamp (e.g. recent-summary-facts-budgeted)
     */
    public void recordContextInputTokens(long rawTokens, long budgetedTokens, String strategy) {
        if (meterRegistry == null) {
            return;
        }
        String strategyTag = safe(strategy);
        DistributionSummary.builder("zhitu_context_input_tokens")
                .tag("phase", "raw")
                .tag("strategy", strategyTag)
                .baseUnit("tokens")
                .register(meterRegistry)
                .record(Math.max(0, rawTokens));
        DistributionSummary.builder("zhitu_context_input_tokens")
                .tag("phase", "budgeted")
                .tag("strategy", strategyTag)
                .baseUnit("tokens")
                .register(meterRegistry)
                .record(Math.max(0, budgetedTokens));
        long saved = Math.max(0, rawTokens - budgetedTokens);
        if (saved > 0) {
            Counter.builder("zhitu_context_token_reduction_total")
                    .tag("strategy", strategyTag)
                    .baseUnit("tokens")
                    .register(meterRegistry)
                    .increment(saved);
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
