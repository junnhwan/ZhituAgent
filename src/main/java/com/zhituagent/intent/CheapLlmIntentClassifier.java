package com.zhituagent.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.config.LlmProperties;
import com.zhituagent.llm.LlmRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tier-2 classifier: lets a cheap LLM (e.g. gpt-5.4-mini) decide between a
 * fixed enum of intent labels. Used only when the rule layer falls through.
 *
 * <p><b>Why a separate cheap-LLM bean</b>: routing every chat through the
 * primary expensive model just to pick a label is the exact cost the M1
 * borrow is meant to cut. By depending on the {@code @Qualifier("fallbackLlm")}
 * bean produced by {@link com.zhituagent.config.ModelRouterConfig}, we reuse
 * the same cheap runtime that backs the M2 fallback path — one mini bean,
 * three jobs (fallback, classifier, scorer-in-M3).
 *
 * <p><b>Latency protection</b>: the cheap LLM call is wrapped in a
 * {@code CompletableFuture} with a hard timeout (default 800 ms via
 * {@link LlmProperties.Intent#getCheapLlmTimeoutMs()}). On timeout or any
 * exception, returns {@link IntentResult#fallthrough(long)} — the existing
 * expensive routing path then runs unchanged. This bounds the worst-case TTFB
 * impact of M1 to {@code timeoutMs + rule eval}.
 *
 * <p><b>Output parsing</b>: the prompt is constrained to JSON
 * {@code {"intent": "...", "confidence": 0.x, "reason": "..."}}. Anything that
 * doesn't parse cleanly into a known {@link IntentLabel} → fallthrough.
 */
@Component
@ConditionalOnProperty(prefix = "zhitu.llm.intent.dual-layer", name = "enabled", havingValue = "true")
@ConditionalOnBean(name = "fallbackLlm")
public class CheapLlmIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(CheapLlmIntentClassifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are a fast intent classifier. Read the user message and reply with
            STRICT JSON only — no prose, no markdown fences. Schema:
            {"intent": "<one of: TIME_QUERY|GREETING|RAG_RETRIEVAL|TOOL_CALL|MULTI_AGENT|UNKNOWN>",
             "confidence": <number 0.0-1.0>,
             "reason": "<1 short sentence>"}

            Label guidance:
            - TIME_QUERY: explicit ask for current time/date/day-of-week.
            - GREETING: pure social greeting with no question content.
            - RAG_RETRIEVAL: factual question that benefits from documents lookup.
            - TOOL_CALL: needs a non-time computational tool (calculator, lookup,
              external API). Prefer RAG_RETRIEVAL when in doubt.
            - MULTI_AGENT: complex multi-step request (SRE incident triage, multi-tool plan).
            - UNKNOWN: cannot decide confidently. Use confidence < 0.5.
            """;

    private final LlmRuntime cheapLlm;
    private final long timeoutMs;

    public CheapLlmIntentClassifier(@Qualifier("fallbackLlm") LlmRuntime cheapLlm,
                                    LlmProperties llmProperties) {
        this(cheapLlm, llmProperties.getIntent().getCheapLlmTimeoutMs());
    }

    /** Test-friendly constructor accepting the timeout directly. */
    CheapLlmIntentClassifier(LlmRuntime cheapLlm, long timeoutMs) {
        this.cheapLlm = cheapLlm;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public IntentResult classify(String userMessage, Map<String, Object> sessionMetadata) {
        long startNanos = System.nanoTime();
        if (userMessage == null || userMessage.isBlank()) {
            return IntentResult.fallthrough(elapsedMs(startNanos));
        }
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                    cheapLlm.generate(SYSTEM_PROMPT, java.util.List.of("USER: " + userMessage),
                            Map.of("phase", "intent-classify"))
            );
            String raw = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return parse(raw, elapsedMs(startNanos));
        } catch (TimeoutException timeout) {
            log.warn("cheap llm intent classifier timed out after {}ms — falling through", timeoutMs);
            return IntentResult.fallthrough(elapsedMs(startNanos));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return IntentResult.fallthrough(elapsedMs(startNanos));
        } catch (ExecutionException | RuntimeException exception) {
            log.warn(
                    "cheap llm intent classifier failed — falling through error={}",
                    exception.getMessage()
            );
            return IntentResult.fallthrough(elapsedMs(startNanos));
        }
    }

    private static IntentResult parse(String raw, long latencyMs) {
        if (raw == null || raw.isBlank()) {
            return IntentResult.fallthrough(latencyMs);
        }
        // Strip code fences if the model added them despite instructions.
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            String intentStr = node.path("intent").asText("UNKNOWN");
            double confidence = node.path("confidence").asDouble(0.0);
            IntentLabel label;
            try {
                label = IntentLabel.valueOf(intentStr);
            } catch (IllegalArgumentException unknownLabel) {
                return IntentResult.fallthrough(latencyMs);
            }
            if (label == IntentLabel.UNKNOWN || label == IntentLabel.FALLTHROUGH) {
                return IntentResult.fallthrough(latencyMs);
            }
            return IntentResult.cheapLlm(label, clamp(confidence), latencyMs);
        } catch (Exception parseFailure) {
            // Bad JSON or any other parse oddity — fall through.
            return IntentResult.fallthrough(latencyMs);
        }
    }

    private static double clamp(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Test inspection hook: read effective timeout. */
    long timeoutMs() {
        return timeoutMs;
    }

    /** Avoid unused-warning when {@code HashMap} is not directly used. */
    @SuppressWarnings("unused")
    private static final Map<String, Object> EMPTY_CTX = new HashMap<>();
}
