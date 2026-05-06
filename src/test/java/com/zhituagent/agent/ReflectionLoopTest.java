package com.zhituagent.agent;

import com.zhituagent.context.ContextBundle;
import com.zhituagent.orchestrator.AgentLoop;
import com.zhituagent.orchestrator.ToolCallExecutor;
import com.zhituagent.trace.SpanCollector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReflectionLoopTest {

    private final SpanCollector spans = new SpanCollector();

    @Test
    void disabledModeIsBytePassthroughOfAgentLoop() {
        AgentLoop agent = mock(AgentLoop.class);
        AgentLoop.LoopResult result = sampleResult("answer", List.of());
        when(agent.run(anyString(), anyString(), any(), anyMap(), anyInt(), any())).thenReturn(result);

        ReflectionLoop loop = new ReflectionLoop(agent, /*reflectionAgent*/ null, spans, false, 8, 1);
        AgentLoop.LoopResult got = loop.run("sys", "msg", null, Map.of(), 4);

        assertThat(got).isSameAs(result);
        verify(agent, times(1)).run(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void scoreAtOrAboveThresholdReturnsFirstResultNoRetry() {
        AgentLoop agent = mock(AgentLoop.class);
        AgentLoop.LoopResult result = sampleResult("good", List.of());
        when(agent.run(anyString(), anyString(), any(), anyMap(), anyInt(), any())).thenReturn(result);

        StubScorer scorer = StubScorer.returning(new ReflectionVerdict(9, "clear", "", true));
        ReflectionLoop loop = new ReflectionLoop(agent, scorer.asReflectionAgent(), spans, true, 8, 1);

        AgentLoop.LoopResult got = loop.run("sys", "q", null, Map.of(), 4);

        assertThat(got).isSameAs(result);
        verify(agent, times(1)).run(any(), any(), any(), any(), anyInt(), any());
        assertThat(scorer.calls.get()).isEqualTo(1);
    }

    @Test
    void lowScoreTriggersOneRetryWithReviewerFeedbackInPrompt() {
        AgentLoop agent = mock(AgentLoop.class);
        AgentLoop.LoopResult first = sampleResult("weak", List.of());
        AgentLoop.LoopResult retry = sampleResult("better", List.of());
        when(agent.run(anyString(), anyString(), any(), anyMap(), anyInt(), any()))
                .thenReturn(first, retry);

        StubScorer scorer = StubScorer.returning(
                new ReflectionVerdict(5, "missed key facts", "address point X", false)
        );
        ReflectionLoop loop = new ReflectionLoop(agent, scorer.asReflectionAgent(), spans, true, 8, 1);

        AgentLoop.LoopResult got = loop.run("ORIGINAL_SYS_PROMPT", "q", null, Map.of(), 4);

        assertThat(got).isSameAs(retry);
        // 2 AgentLoop runs: original + retry
        verify(agent, times(2)).run(any(), any(), any(), any(), anyInt(), any());
        // Reviewer feedback must be appended to the system prompt of the retry.
        org.mockito.ArgumentCaptor<String> sysPromptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(agent, times(2)).run(sysPromptCaptor.capture(), any(), any(), any(), anyInt(), any());
        String retrySys = sysPromptCaptor.getAllValues().get(1);
        assertThat(retrySys).contains("ORIGINAL_SYS_PROMPT");
        assertThat(retrySys).contains("REVIEWER FEEDBACK");
        assertThat(retrySys).contains("missed key facts");
        assertThat(retrySys).contains("address point X");
        // Scorer called once (only on first answer; we don't re-score after retry to bound cost).
        assertThat(scorer.calls.get()).isEqualTo(1);
    }

    @Test
    void maxRetriesZeroDisablesRetryEvenWhenScoreLow() {
        AgentLoop agent = mock(AgentLoop.class);
        AgentLoop.LoopResult first = sampleResult("weak", List.of());
        when(agent.run(anyString(), anyString(), any(), anyMap(), anyInt(), any())).thenReturn(first);

        StubScorer scorer = StubScorer.returning(new ReflectionVerdict(3, "bad", "", false));
        ReflectionLoop loop = new ReflectionLoop(agent, scorer.asReflectionAgent(), spans, true, 8, 0);

        AgentLoop.LoopResult got = loop.run("sys", "q", null, Map.of(), 4);

        assertThat(got).isSameAs(first);
        verify(agent, times(1)).run(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void noReflectionAgentBeanFallsBackToPassthrough() {
        AgentLoop agent = mock(AgentLoop.class);
        AgentLoop.LoopResult result = sampleResult("any", List.of());
        when(agent.run(anyString(), anyString(), any(), anyMap(), anyInt(), any())).thenReturn(result);

        // enabled=true but reflectionAgent=null (e.g. config inconsistency)
        ReflectionLoop loop = new ReflectionLoop(agent, null, spans, true, 8, 1);
        AgentLoop.LoopResult got = loop.run("sys", "q", null, Map.of(), 4);

        assertThat(got).isSameAs(result);
        verify(agent, never()).run(anyString(),
                org.mockito.ArgumentMatchers.argThat(arg -> false),  // never matches
                any(), any(), anyInt(), any());
    }

    private static AgentLoop.LoopResult sampleResult(String text, List<ToolCallExecutor.ToolExecution> exec) {
        return new AgentLoop.LoopResult(text, 1, true, exec, Map.of());
    }

    /** Thin scorer adapter usable as a ReflectionAgent. */
    private static final class StubScorer {
        final AtomicInteger calls = new AtomicInteger();
        private final ReflectionVerdict fixed;
        private StubScorer(ReflectionVerdict v) { this.fixed = v; }
        static StubScorer returning(ReflectionVerdict v) { return new StubScorer(v); }

        ReflectionAgent asReflectionAgent() {
            // We need a ReflectionAgent instance whose score() returns the fixed verdict.
            // Easiest path: anonymous subclass overriding score().
            return new ReflectionAgent(new com.zhituagent.llm.LlmRuntime() {
                @Override public String generate(String s, List<String> m, Map<String, Object> meta) { return ""; }
                @Override public void stream(String s, List<String> m, Map<String, Object> meta,
                                             java.util.function.Consumer<String> ot, Runnable oc) { oc.run(); }
            }, 8) {
                @Override
                public ReflectionVerdict score(String originalQuery, String candidateAnswer, List<String> toolsUsed) {
                    calls.incrementAndGet();
                    return fixed;
                }
            };
        }
    }
}
