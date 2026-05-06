package com.zhituagent.llm;

import com.zhituagent.config.LlmProperties;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RoutingLlmRuntime}. Uses lightweight stub
 * {@link LlmRuntime} implementations (not Mockito) so we can deterministically
 * control success/failure per call and inspect token-emit ordering for the
 * streaming fallback rule.
 */
class RoutingLlmRuntimeTest {

    private LlmProperties.Router.CircuitBreaker cbConfig;

    @BeforeEach
    void setUp() {
        cbConfig = new LlmProperties.Router.CircuitBreaker();
        // Tight thresholds so tests trip CB quickly without waiting.
        cbConfig.setMinimumNumberOfCalls(2);
        cbConfig.setSlidingWindowSize(2);
        cbConfig.setFailureRateThreshold(50);
        cbConfig.setWaitDurationInOpenStateMs(50);
        cbConfig.setPermittedCallsInHalfOpenState(1);
        cbConfig.setSlowCallDurationThresholdMs(60_000);
        cbConfig.setSlowCallRateThreshold(100);
    }

    @Test
    void primarySuccessReturnsPrimaryAnswerWithoutTouchingFallback() {
        StubLlmRuntime primary = StubLlmRuntime.alwaysReturning("primary-OK");
        StubLlmRuntime fallback = StubLlmRuntime.alwaysReturning("fallback-OK");
        RoutingLlmRuntime routing = new RoutingLlmRuntime(primary, fallback, cbConfig, null);

        String answer = routing.generate("sys", List.of("USER: hi"), Map.of());

        assertThat(answer).isEqualTo("primary-OK");
        assertThat(primary.callCount()).isEqualTo(1);
        assertThat(fallback.callCount()).isZero();
    }

    @Test
    void primaryFailureRoutesToFallbackTransparently() {
        StubLlmRuntime primary = StubLlmRuntime.alwaysFailing("primary down");
        StubLlmRuntime fallback = StubLlmRuntime.alwaysReturning("fallback-OK");
        RoutingLlmRuntime routing = new RoutingLlmRuntime(primary, fallback, cbConfig, null);

        String answer = routing.generate("sys", List.of("USER: hi"), Map.of());

        assertThat(answer).isEqualTo("fallback-OK");
        assertThat(primary.callCount()).isEqualTo(1);
        assertThat(fallback.callCount()).isEqualTo(1);
    }

    @Test
    void primaryCircuitTripsAfterFailureRateThresholdAndSkipsPrimaryEntirely() {
        StubLlmRuntime primary = StubLlmRuntime.alwaysFailing("down");
        StubLlmRuntime fallback = StubLlmRuntime.alwaysReturning("ok");
        RoutingLlmRuntime routing = new RoutingLlmRuntime(primary, fallback, cbConfig, null);

        // 2 calls — both fail on primary, both succeed on fallback. After this,
        // primary CB should be OPEN (50% failure rate over min 2 calls).
        routing.generate("sys", List.of("USER: a"), Map.of());
        routing.generate("sys", List.of("USER: b"), Map.of());

        assertThat(routing.primaryCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int primaryBefore = primary.callCount();
        // Third call: primary CB is OPEN, must skip primary, use fallback.
        String answer = routing.generate("sys", List.of("USER: c"), Map.of());
        assertThat(answer).isEqualTo("ok");
        assertThat(primary.callCount()).isEqualTo(primaryBefore); // primary NOT invoked
    }

    @Test
    void primaryCircuitMovesToHalfOpenAfterWaitDurationAndClosesOnSuccess() throws InterruptedException {
        FlakyStub primary = new FlakyStub();
        StubLlmRuntime fallback = StubLlmRuntime.alwaysReturning("fb");
        RoutingLlmRuntime routing = new RoutingLlmRuntime(primary, fallback, cbConfig, null);

        primary.failNext(2);
        routing.generate("sys", List.of("USER: a"), Map.of());
        routing.generate("sys", List.of("USER: b"), Map.of());
        assertThat(routing.primaryCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Wait past waitDurationInOpenStateMs (50ms) to allow probe.
        Thread.sleep(80);

        // Primary will succeed for the probe — CB should transition CLOSED.
        primary.succeedNext("probe-ok");
        String probe = routing.generate("sys", List.of("USER: probe"), Map.of());
        assertThat(probe).isEqualTo("probe-ok");
        assertThat(routing.primaryCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void bothCircuitsOpenThrowsExhaustedException() {
        StubLlmRuntime primary = StubLlmRuntime.alwaysFailing("p");
        StubLlmRuntime fallback = StubLlmRuntime.alwaysFailing("f");
        RoutingLlmRuntime routing = new RoutingLlmRuntime(primary, fallback, cbConfig, null);

        // Trip both CBs.
        for (int i = 0; i < 2; i++) {
            try {
                routing.generate("sys", List.of("USER: " + i), Map.of());
            } catch (RuntimeException ignored) {
                // expected: fallback throws after primary fails
            }
        }

        assertThat(routing.primaryCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(routing.fallbackCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> routing.generate("sys", List.of("USER: x"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm router exhausted");
    }

    @Test
    void streamFallsBackWhenPrimaryFailsBeforeFirstToken() {
        StubLlmRuntime primary = StubLlmRuntime.streamFailingBeforeFirstToken("primary stream down");
        StubLlmRuntime fallback = StubLlmRuntime.streamingTokens(List.of("fb-1", "fb-2"));
        RoutingLlmRuntime routing = new RoutingLlmRuntime(primary, fallback, cbConfig, null);

        java.util.List<String> tokens = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        routing.stream("sys", List.of("USER: hi"), Map.of(), tokens::add, completed::incrementAndGet);

        assertThat(tokens).containsExactly("fb-1", "fb-2");
        assertThat(completed.get()).isEqualTo(1);
    }

    @Test
    void streamPropagatesFailureWhenPrimaryFailsAfterFirstToken() {
        StubLlmRuntime primary = StubLlmRuntime.streamEmittingThenFailing("p-1", "primary mid-stream boom");
        StubLlmRuntime fallback = StubLlmRuntime.streamingTokens(List.of("FALLBACK-must-not-emit"));
        RoutingLlmRuntime routing = new RoutingLlmRuntime(primary, fallback, cbConfig, null);

        java.util.List<String> tokens = new java.util.ArrayList<>();
        assertThatThrownBy(() ->
                routing.stream("sys", List.of("USER: hi"), Map.of(), tokens::add, () -> { })
        ).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("primary mid-stream boom");

        // First primary token already emitted; fallback must NOT have run.
        assertThat(tokens).containsExactly("p-1");
        assertThat(fallback.streamCallCount()).isZero();
    }

    @Test
    void generateWithToolsAndChatTurnAlsoFallbackOnPrimaryFailure() {
        StubLlmRuntime primary = StubLlmRuntime.alwaysFailing("down");
        StubLlmRuntime fallback = StubLlmRuntime.alwaysReturning("fb");
        RoutingLlmRuntime routing = new RoutingLlmRuntime(primary, fallback, cbConfig, null);

        ChatTurnResult tools = routing.generateWithTools("sys", List.of("USER: x"), List.of(), Map.of());
        ChatTurnResult chat = routing.generateChatTurn("sys", List.<ChatMessage>of(), List.of(), Map.of());

        assertThat(tools.text()).isEqualTo("fb");
        assertThat(chat.text()).isEqualTo("fb");
        assertThat(fallback.callCount()).isEqualTo(2);
    }

    // --- Stub helpers ---

    /** Configurable stub: always returns or always throws on every entry point. */
    private static final class StubLlmRuntime implements LlmRuntime {

        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicInteger streamCallCount = new AtomicInteger();
        private final String answer;
        private final RuntimeException failure;
        private final List<String> streamTokens;
        private final boolean streamFailBeforeFirst;
        private final String streamEmitFirst;
        private final String streamFailAfterFirst;

        private StubLlmRuntime(String answer,
                               RuntimeException failure,
                               List<String> streamTokens,
                               boolean streamFailBeforeFirst,
                               String streamEmitFirst,
                               String streamFailAfterFirst) {
            this.answer = answer;
            this.failure = failure;
            this.streamTokens = streamTokens;
            this.streamFailBeforeFirst = streamFailBeforeFirst;
            this.streamEmitFirst = streamEmitFirst;
            this.streamFailAfterFirst = streamFailAfterFirst;
        }

        static StubLlmRuntime alwaysReturning(String answer) {
            return new StubLlmRuntime(answer, null, List.of(answer), false, null, null);
        }

        static StubLlmRuntime alwaysFailing(String message) {
            return new StubLlmRuntime(null, new RuntimeException(message), List.of(),
                    true, null, message);
        }

        static StubLlmRuntime streamingTokens(List<String> tokens) {
            return new StubLlmRuntime("", null, tokens, false, null, null);
        }

        static StubLlmRuntime streamFailingBeforeFirstToken(String message) {
            return new StubLlmRuntime(null, new RuntimeException(message), List.of(),
                    true, null, null);
        }

        static StubLlmRuntime streamEmittingThenFailing(String firstToken, String message) {
            return new StubLlmRuntime(null, null, List.of(),
                    false, firstToken, message);
        }

        int callCount() { return callCount.get(); }
        int streamCallCount() { return streamCallCount.get(); }

        @Override
        public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
            callCount.incrementAndGet();
            if (failure != null) throw failure;
            return answer;
        }

        @Override
        public void stream(String systemPrompt, List<String> messages, Map<String, Object> metadata,
                           Consumer<String> onToken, Runnable onComplete) {
            streamCallCount.incrementAndGet();
            if (streamFailBeforeFirst && failure != null) {
                throw failure;
            }
            if (streamEmitFirst != null) {
                onToken.accept(streamEmitFirst);
                if (streamFailAfterFirst != null) {
                    throw new RuntimeException(streamFailAfterFirst);
                }
            }
            for (String token : streamTokens) {
                onToken.accept(token);
            }
            onComplete.run();
        }

        @Override
        public ChatTurnResult generateWithTools(String systemPrompt, List<String> messages,
                                                List<ToolSpecification> tools, Map<String, Object> metadata) {
            callCount.incrementAndGet();
            if (failure != null) throw failure;
            return ChatTurnResult.ofText(answer);
        }

        @Override
        public ChatTurnResult generateChatTurn(String systemPrompt, List<ChatMessage> messages,
                                               List<ToolSpecification> tools, Map<String, Object> metadata) {
            callCount.incrementAndGet();
            if (failure != null) throw failure;
            return ChatTurnResult.ofText(answer);
        }
    }

    /** Stub whose next-N calls fail and subsequent calls succeed with a configured answer. */
    private static final class FlakyStub implements LlmRuntime {

        private int remainingFailures;
        private String nextAnswer = "ok";

        void failNext(int n) { this.remainingFailures = n; }
        void succeedNext(String answer) { this.nextAnswer = answer; this.remainingFailures = 0; }

        @Override
        public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
            if (remainingFailures > 0) {
                remainingFailures--;
                throw new RuntimeException("flaky");
            }
            return nextAnswer;
        }

        @Override
        public void stream(String systemPrompt, List<String> messages, Map<String, Object> metadata,
                           Consumer<String> onToken, Runnable onComplete) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
