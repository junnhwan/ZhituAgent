package com.zhituagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryMetricsRecorderTest {

    @Test
    void shouldRecordRawAndBudgetedDistributionsAndReductionCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryMetricsRecorder recorder = new MemoryMetricsRecorder(registry);

        recorder.recordContextInputTokens(1500, 1000, "recent-summary-facts-budgeted");

        DistributionSummary raw = registry.find("zhitu_context_input_tokens")
                .tag("phase", "raw")
                .tag("strategy", "recent-summary-facts-budgeted")
                .summary();
        DistributionSummary budgeted = registry.find("zhitu_context_input_tokens")
                .tag("phase", "budgeted")
                .tag("strategy", "recent-summary-facts-budgeted")
                .summary();
        Counter reduction = registry.find("zhitu_context_token_reduction_total")
                .tag("strategy", "recent-summary-facts-budgeted")
                .counter();

        assertThat(raw).isNotNull();
        assertThat(raw.count()).isEqualTo(1);
        assertThat(raw.totalAmount()).isEqualTo(1500.0);
        assertThat(budgeted).isNotNull();
        assertThat(budgeted.count()).isEqualTo(1);
        assertThat(budgeted.totalAmount()).isEqualTo(1000.0);
        assertThat(reduction).isNotNull();
        assertThat(reduction.count()).isEqualTo(500.0);
    }

    @Test
    void shouldNotEmitReductionCounterWhenBudgetedExceedsRaw() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryMetricsRecorder recorder = new MemoryMetricsRecorder(registry);

        recorder.recordContextInputTokens(800, 800, "recent-summary");

        Counter reduction = registry.find("zhitu_context_token_reduction_total")
                .tag("strategy", "recent-summary")
                .counter();

        assertThat(reduction).isNull();
    }

    @Test
    void shouldFallBackToUnknownStrategyTagWhenStrategyIsBlank() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryMetricsRecorder recorder = new MemoryMetricsRecorder(registry);

        recorder.recordContextInputTokens(100, 50, null);
        recorder.recordContextInputTokens(200, 100, "");

        DistributionSummary raw = registry.find("zhitu_context_input_tokens")
                .tag("phase", "raw")
                .tag("strategy", "unknown")
                .summary();
        assertThat(raw).isNotNull();
        assertThat(raw.count()).isEqualTo(2);
    }

    @Test
    void shouldBeNoopWhenMeterRegistryIsNull() {
        MemoryMetricsRecorder recorder = MemoryMetricsRecorder.noop();
        recorder.recordContextInputTokens(1000, 500, "any-strategy");
        recorder.recordCompression("ok", "redis");
    }

    @Test
    void shouldRecordCompressionCounterByOutcomeAndStore() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryMetricsRecorder recorder = new MemoryMetricsRecorder(registry);

        recorder.recordCompression("ok", "redis");
        recorder.recordCompression("skip", "redis");

        Counter ok = registry.find("zhitu_memory_compression_total")
                .tag("outcome", "ok").tag("store", "redis").counter();
        Counter skip = registry.find("zhitu_memory_compression_total")
                .tag("outcome", "skip").tag("store", "redis").counter();
        assertThat(ok).isNotNull();
        assertThat(ok.count()).isEqualTo(1.0);
        assertThat(skip).isNotNull();
        assertThat(skip.count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordSummaryMetricsByOutcomeModelLatencyAndTokens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryMetricsRecorder recorder = new MemoryMetricsRecorder(registry);

        recorder.recordSummary("success", "gpt-5.4-mini", 123, 456, 78);

        Counter total = registry.find("zhitu_memory_summary_total")
                .tag("outcome", "success")
                .tag("model", "gpt-5.4-mini")
                .counter();
        DistributionSummary latency = registry.find("zhitu_memory_summary_latency_ms")
                .tag("model", "gpt-5.4-mini")
                .summary();
        DistributionSummary inputTokens = registry.find("zhitu_memory_summary_tokens")
                .tag("phase", "input")
                .tag("model", "gpt-5.4-mini")
                .summary();
        DistributionSummary outputTokens = registry.find("zhitu_memory_summary_tokens")
                .tag("phase", "output")
                .tag("model", "gpt-5.4-mini")
                .summary();

        assertThat(total).isNotNull();
        assertThat(total.count()).isEqualTo(1.0);
        assertThat(latency).isNotNull();
        assertThat(latency.totalAmount()).isEqualTo(123.0);
        assertThat(inputTokens).isNotNull();
        assertThat(inputTokens.totalAmount()).isEqualTo(456.0);
        assertThat(outputTokens).isNotNull();
        assertThat(outputTokens.totalAmount()).isEqualTo(78.0);
    }
}
