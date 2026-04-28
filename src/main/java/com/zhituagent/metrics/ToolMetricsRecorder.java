package com.zhituagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ToolMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public ToolMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public static ToolMetricsRecorder noop() {
        return new ToolMetricsRecorder(null);
    }

    public void recordInvocation(String toolName, boolean success) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("zhitu_tool_invocations_total")
                .tag("tool", safe(toolName))
                .tag("success", Boolean.toString(success))
                .register(meterRegistry)
                .increment();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
