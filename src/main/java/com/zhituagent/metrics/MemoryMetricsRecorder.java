package com.zhituagent.metrics;

import io.micrometer.core.instrument.Counter;
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

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
