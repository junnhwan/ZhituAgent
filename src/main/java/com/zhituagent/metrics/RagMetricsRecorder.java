package com.zhituagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RagMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public RagMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public static RagMetricsRecorder noop() {
        return new RagMetricsRecorder(null);
    }

    public void recordRetrieval(String retrievalMode, boolean hit, long latencyMs, int candidateCount, int resultCount) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("zhitu_rag_retrieval_total")
                .tag("retrieval_mode", safe(retrievalMode))
                .tag("hit", Boolean.toString(hit))
                .register(meterRegistry)
                .increment();

        Timer.builder("zhitu_rag_retrieval_duration_seconds")
                .tag("retrieval_mode", safe(retrievalMode))
                .tag("hit", Boolean.toString(hit))
                .register(meterRegistry)
                .record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);

        DistributionSummary.builder("zhitu_rag_recall_size")
                .tag("retrieval_mode", safe(retrievalMode))
                .tag("kind", "candidates")
                .register(meterRegistry)
                .record(Math.max(0, candidateCount));

        DistributionSummary.builder("zhitu_rag_recall_size")
                .tag("retrieval_mode", safe(retrievalMode))
                .tag("kind", "results")
                .register(meterRegistry)
                .record(Math.max(0, resultCount));
    }

    public void recordRerankAttempt(String model, boolean success) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("zhitu_rerank_requests_total")
                .tag("model", safe(model))
                .tag("success", Boolean.toString(success))
                .register(meterRegistry)
                .increment();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
