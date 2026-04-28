package com.zhituagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ChatMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public ChatMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public static ChatMetricsRecorder noop() {
        return new ChatMetricsRecorder(null);
    }

    public void recordRequest(String path, boolean stream, boolean success, long latencyMs) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("zhitu_chat_requests_total")
                .tag("path", safe(path))
                .tag("stream", Boolean.toString(stream))
                .tag("success", Boolean.toString(success))
                .register(meterRegistry)
                .increment();

        Timer.builder("zhitu_chat_request_duration_seconds")
                .tag("path", safe(path))
                .tag("stream", Boolean.toString(stream))
                .tag("success", Boolean.toString(success))
                .register(meterRegistry)
                .record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
