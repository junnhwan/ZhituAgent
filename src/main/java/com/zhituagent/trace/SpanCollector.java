package com.zhituagent.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-request collector that builds a {@link Span} tree as the chat turn flows
 * through phases. Backed by a {@code ThreadLocal} since each web request is
 * handled on a single thread; sub-tasks that fan out to other threads (parallel
 * tool calls in {@code ToolCallExecutor}) still record their spans synchronously
 * after {@code .join()} so the linear-on-thread assumption holds.
 *
 * <p>Lifecycle: caller invokes {@link #beginTrace()} once per request, opens
 * spans via {@link #startSpan}, closes them via {@link #endSpan}, then drains
 * the full tree with {@link #drain()} which also clears the ThreadLocal.
 */
@Component
public class SpanCollector {

    private static final Logger log = LoggerFactory.getLogger(SpanCollector.class);

    private final ThreadLocal<TraceState> state = new ThreadLocal<>();

    public String beginTrace() {
        TraceState fresh = new TraceState();
        fresh.traceId = UUID.randomUUID().toString();
        state.set(fresh);
        return fresh.traceId;
    }

    public String currentTraceId() {
        TraceState current = state.get();
        return current == null ? null : current.traceId;
    }

    public String startSpan(String name, String kind) {
        return startSpan(name, kind, Map.of());
    }

    public String startSpan(String name, String kind, Map<String, Object> attributes) {
        TraceState current = state.get();
        if (current == null) {
            log.debug("startSpan invoked without active trace, ignoring name={}", name);
            return null;
        }
        String spanId = UUID.randomUUID().toString();
        OpenSpan open = new OpenSpan();
        open.spanId = spanId;
        open.parentSpanId = current.stack.isEmpty() ? null : current.stack.peek();
        open.name = name;
        open.kind = kind;
        open.startEpochMillis = System.currentTimeMillis();
        open.attributes = new HashMap<>(attributes == null ? Map.of() : attributes);
        current.openSpans.put(spanId, open);
        current.stack.push(spanId);
        return spanId;
    }

    public void endSpan(String spanId, String status) {
        endSpan(spanId, status, Map.of());
    }

    public void endSpan(String spanId, String status, Map<String, Object> additionalAttributes) {
        TraceState current = state.get();
        if (current == null || spanId == null) {
            return;
        }
        OpenSpan open = current.openSpans.remove(spanId);
        if (open == null) {
            return;
        }
        if (additionalAttributes != null && !additionalAttributes.isEmpty()) {
            open.attributes.putAll(additionalAttributes);
        }
        open.status = status == null ? "ok" : status;
        open.endEpochMillis = System.currentTimeMillis();
        current.stack.remove(spanId);
        current.completed.add(new Span(
                open.spanId,
                open.parentSpanId,
                current.traceId,
                open.name,
                open.kind,
                open.startEpochMillis,
                open.endEpochMillis,
                open.status,
                Map.copyOf(open.attributes)
        ));
    }

    public List<Span> drain() {
        TraceState current = state.get();
        if (current == null) {
            return List.of();
        }
        // Close any spans the caller forgot — record them as "incomplete" so the trace tree is still well-formed.
        for (OpenSpan open : new ArrayList<>(current.openSpans.values())) {
            log.warn("trace.span.unclosed name={} kind={}", open.name, open.kind);
            open.endEpochMillis = System.currentTimeMillis();
            open.status = "incomplete";
            current.completed.add(new Span(
                    open.spanId,
                    open.parentSpanId,
                    current.traceId,
                    open.name,
                    open.kind,
                    open.startEpochMillis,
                    open.endEpochMillis,
                    open.status,
                    Map.copyOf(open.attributes)
            ));
        }
        List<Span> drained = List.copyOf(current.completed);
        state.remove();
        return drained;
    }

    private static final class TraceState {
        String traceId;
        final Deque<String> stack = new ArrayDeque<>();
        final Map<String, OpenSpan> openSpans = new HashMap<>();
        final List<Span> completed = new ArrayList<>();
    }

    private static final class OpenSpan {
        String spanId;
        String parentSpanId;
        String name;
        String kind;
        long startEpochMillis;
        long endEpochMillis;
        String status;
        Map<String, Object> attributes;
    }
}
