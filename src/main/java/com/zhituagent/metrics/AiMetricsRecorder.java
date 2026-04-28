package com.zhituagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AiMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public AiMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public static AiMetricsRecorder noop() {
        return new AiMetricsRecorder(null);
    }

    public void recordRequest(String model, String mode, boolean success, long latencyMs) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("zhitu_llm_requests_total")
                .tag("model", safe(model))
                .tag("mode", safe(mode))
                .tag("success", Boolean.toString(success))
                .register(meterRegistry)
                .increment();

        Timer.builder("zhitu_llm_request_duration_seconds")
                .tag("model", safe(model))
                .tag("mode", safe(mode))
                .tag("success", Boolean.toString(success))
                .register(meterRegistry)
                .record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
