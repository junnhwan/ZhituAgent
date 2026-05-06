package com.zhituagent.llm;

import com.zhituagent.config.LlmProperties;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link LlmRuntime} decorator that routes calls to a primary runtime first and
 * falls back to a secondary one when the primary's circuit breaker trips or the
 * primary call fails.
 *
 * <p><b>Why two CBs (one per tier)</b>: a single shared CB would conflate primary
 * and fallback failures into one error budget, hiding when the fallback itself
 * starts misbehaving. Independent breakers also let HALF_OPEN probes happen
 * separately — the primary can recover while traffic continues on fallback.
 *
 * <p><b>Streaming fallback rule</b>: only the prelude (before any token has been
 * forwarded to the user) is eligible for fallback. Once even one token has been
 * emitted via the user's {@code onToken} consumer, mid-stream errors are
 * propagated as-is; switching providers mid-SSE-stream would corrupt the frame
 * sequence the browser is consuming. See {@link #stream}.
 *
 * <p><b>RateLimiter ordering</b>: rate limiting lives <i>inside</i> each tier
 * runtime ({@code LangChain4jLlmRuntime} acquires the limiter before each
 * provider call). The CB is the outer layer here — so when the limiter blocks,
 * it does <i>not</i> count as a CB failure and the OPEN state still reflects
 * actual provider health.
 *
 * <p>Bean wired by {@link com.zhituagent.config.ModelRouterConfig} when
 * {@code zhitu.llm.router.enabled=true}.
 */
public class RoutingLlmRuntime implements LlmRuntime {

    private static final Logger log = LoggerFactory.getLogger(RoutingLlmRuntime.class);
    private static final String PRIMARY_CB = "llm-primary";
    private static final String FALLBACK_CB = "llm-fallback";

    private final LlmRuntime primary;
    private final LlmRuntime fallback;
    private final CircuitBreaker primaryCb;
    private final CircuitBreaker fallbackCb;

    public RoutingLlmRuntime(LlmRuntime primary,
                             LlmRuntime fallback,
                             LlmProperties.Router.CircuitBreaker cbConfig,
                             MeterRegistry meterRegistry) {
        this.primary = primary;
        this.fallback = fallback;
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(buildConfig(cbConfig));
        this.primaryCb = registry.circuitBreaker(PRIMARY_CB);
        this.fallbackCb = registry.circuitBreaker(FALLBACK_CB);
        if (meterRegistry != null) {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        }
        log.info(
                "llm router enabled primaryCb={} fallbackCb={} failureRateThreshold={} slowCallMs={} minCalls={}",
                PRIMARY_CB,
                FALLBACK_CB,
                cbConfig.getFailureRateThreshold(),
                cbConfig.getSlowCallDurationThresholdMs(),
                cbConfig.getMinimumNumberOfCalls()
        );
    }

    @Override
    public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
        return executeWithFallback(
                "generate",
                () -> primary.generate(systemPrompt, messages, metadata),
                () -> fallback.generate(systemPrompt, messages, metadata)
        );
    }

    @Override
    public ChatTurnResult generateWithTools(String systemPrompt,
                                            List<String> messages,
                                            List<ToolSpecification> tools,
                                            Map<String, Object> metadata) {
        return executeWithFallback(
                "generateWithTools",
                () -> primary.generateWithTools(systemPrompt, messages, tools, metadata),
                () -> fallback.generateWithTools(systemPrompt, messages, tools, metadata)
        );
    }

    @Override
    public ChatTurnResult generateChatTurn(String systemPrompt,
                                           List<ChatMessage> messages,
                                           List<ToolSpecification> tools,
                                           Map<String, Object> metadata) {
        return executeWithFallback(
                "generateChatTurn",
                () -> primary.generateChatTurn(systemPrompt, messages, tools, metadata),
                () -> fallback.generateChatTurn(systemPrompt, messages, tools, metadata)
        );
    }

    @Override
    public void stream(String systemPrompt,
                       List<String> messages,
                       Map<String, Object> metadata,
                       Consumer<String> onToken,
                       Runnable onComplete) {
        AtomicBoolean tokenEmitted = new AtomicBoolean(false);
        Consumer<String> guardedOnToken = token -> {
            tokenEmitted.set(true);
            onToken.accept(token);
        };

        if (primaryCb.tryAcquirePermission()) {
            long start = System.nanoTime();
            try {
                primary.stream(systemPrompt, messages, metadata, guardedOnToken, onComplete);
                primaryCb.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                return;
            } catch (RuntimeException exception) {
                primaryCb.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, exception);
                if (tokenEmitted.get()) {
                    log.warn(
                            "llm.route.stream primary failed mid-stream; cannot fallback (already emitted tokens) error={}",
                            exception.getMessage()
                    );
                    throw exception;
                }
                log.warn(
                            "llm.route.stream primary failed before first token; trying fallback error={}",
                            exception.getMessage()
                );
            }
        } else {
            log.debug("llm.route.stream primary CB open ({}); routing straight to fallback", primaryCb.getState());
        }

        if (!fallbackCb.tryAcquirePermission()) {
            throw new IllegalStateException(
                    "llm router exhausted: both primary CB (" + primaryCb.getState()
                            + ") and fallback CB (" + fallbackCb.getState() + ") rejected stream"
            );
        }
        long start = System.nanoTime();
        try {
            // Note: pass user's raw onToken — fallback drives the stream now.
            fallback.stream(systemPrompt, messages, metadata, onToken, onComplete);
            fallbackCb.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        } catch (RuntimeException exception) {
            fallbackCb.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, exception);
            throw exception;
        }
    }

    /**
     * Try primary first. On exception or CB-open, transparently retry on fallback.
     * If the fallback CB is also open or it too errors, propagate the failure.
     */
    private <T> T executeWithFallback(String op, Supplier<T> primaryCall, Supplier<T> fallbackCall) {
        if (primaryCb.tryAcquirePermission()) {
            long start = System.nanoTime();
            try {
                T result = primaryCall.get();
                primaryCb.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                return result;
            } catch (RuntimeException exception) {
                primaryCb.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, exception);
                log.warn(
                        "llm.route.{} primary failed; trying fallback cbState={} error={}",
                        op,
                        primaryCb.getState(),
                        exception.getMessage()
                );
            }
        } else {
            log.debug("llm.route.{} primary CB {} — going straight to fallback", op, primaryCb.getState());
        }

        if (!fallbackCb.tryAcquirePermission()) {
            throw new IllegalStateException(
                    "llm router exhausted: both primary CB (" + primaryCb.getState()
                            + ") and fallback CB (" + fallbackCb.getState() + ") rejected " + op
            );
        }
        long start = System.nanoTime();
        try {
            T result = fallbackCall.get();
            fallbackCb.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException exception) {
            fallbackCb.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, exception);
            throw exception;
        }
    }

    private static CircuitBreakerConfig buildConfig(LlmProperties.Router.CircuitBreaker cfg) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.getFailureRateThreshold())
                .slowCallRateThreshold(cfg.getSlowCallRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(cfg.getSlowCallDurationThresholdMs()))
                .minimumNumberOfCalls(cfg.getMinimumNumberOfCalls())
                .slidingWindowSize(cfg.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofMillis(cfg.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(cfg.getPermittedCallsInHalfOpenState())
                .build();
    }

    /** Test hook: inspect primary CB state. */
    public CircuitBreaker primaryCircuitBreaker() {
        return primaryCb;
    }

    /** Test hook: inspect fallback CB state. */
    public CircuitBreaker fallbackCircuitBreaker() {
        return fallbackCb;
    }
}
