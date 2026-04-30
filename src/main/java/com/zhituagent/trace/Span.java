package com.zhituagent.trace;

import java.util.List;
import java.util.Map;

/**
 * Span tree node — captures a single phase of an agent turn (route decision,
 * RAG retrieval, tool invocation, LLM call, …) with timing and parent linkage.
 *
 * <p>The full list of spans is exposed through {@code TraceInfo.spans} for the
 * frontend (a Trace tree visual) and persisted by {@code TraceArchiveService}
 * for offline replay. {@code parentSpanId == null} marks the request root.
 */
public record Span(
        String spanId,
        String parentSpanId,
        String traceId,
        String name,
        String kind,
        long startEpochMillis,
        long endEpochMillis,
        String status,
        Map<String, Object> attributes
) {

    public Span {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public long durationMillis() {
        return Math.max(0L, endEpochMillis - startEpochMillis);
    }
}
