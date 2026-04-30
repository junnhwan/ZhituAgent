package com.zhituagent.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpanCollectorTest {

    @Test
    void shouldBuildNestedSpanTreeAndDrainOnce() {
        SpanCollector collector = new SpanCollector();
        String traceId = collector.beginTrace();
        assertThat(traceId).isNotBlank();
        assertThat(collector.currentTraceId()).isEqualTo(traceId);

        String root = collector.startSpan("chat.turn", "request");
        String route = collector.startSpan("orchestrator.decide", "route");
        collector.endSpan(route, "ok");
        String llm = collector.startSpan("llm.generate", "llm");
        collector.endSpan(llm, "ok");
        collector.endSpan(root, "ok");

        List<Span> spans = collector.drain();
        assertThat(spans).hasSize(3);
        Span rootSpan = spans.stream().filter(s -> s.spanId().equals(root)).findFirst().orElseThrow();
        Span routeSpan = spans.stream().filter(s -> s.spanId().equals(route)).findFirst().orElseThrow();
        Span llmSpan = spans.stream().filter(s -> s.spanId().equals(llm)).findFirst().orElseThrow();

        assertThat(rootSpan.parentSpanId()).isNull();
        assertThat(routeSpan.parentSpanId()).isEqualTo(root);
        assertThat(llmSpan.parentSpanId()).isEqualTo(root);
        assertThat(rootSpan.traceId()).isEqualTo(traceId);
        assertThat(rootSpan.status()).isEqualTo("ok");
        assertThat(rootSpan.durationMillis()).isGreaterThanOrEqualTo(0L);

        // After drain the trace state is gone.
        assertThat(collector.currentTraceId()).isNull();
        assertThat(collector.drain()).isEmpty();
    }

    @Test
    void shouldCloseUnclosedSpansAsIncompleteOnDrain() {
        SpanCollector collector = new SpanCollector();
        collector.beginTrace();
        String root = collector.startSpan("chat.turn", "request");
        collector.startSpan("dangling", "tool");
        // Forget to close dangling, drain anyway.
        collector.endSpan(root, "error");

        List<Span> spans = collector.drain();
        Span dangling = spans.stream().filter(s -> s.name().equals("dangling")).findFirst().orElseThrow();
        assertThat(dangling.status()).isEqualTo("incomplete");
    }
}
