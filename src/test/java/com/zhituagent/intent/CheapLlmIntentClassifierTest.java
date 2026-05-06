package com.zhituagent.intent;

import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class CheapLlmIntentClassifierTest {

    @Test
    void parsesValidJsonResponseIntoCheapLlmTier() {
        StubLlm stub = StubLlm.respond("{\"intent\":\"TIME_QUERY\",\"confidence\":0.9,\"reason\":\"ok\"}");
        CheapLlmIntentClassifier classifier = new CheapLlmIntentClassifier(stub, 5_000);

        IntentResult result = classifier.classify("当前时间", Map.of());

        assertThat(result.label()).isEqualTo(IntentLabel.TIME_QUERY);
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.tier()).isEqualTo(IntentResult.Tier.CHEAP_LLM);
        assertThat(stub.callCount.get()).isEqualTo(1);
    }

    @Test
    void stripsCodeFenceWrappingBeforeParsing() {
        StubLlm stub = StubLlm.respond("```json\n{\"intent\":\"GREETING\",\"confidence\":0.92}\n```");
        CheapLlmIntentClassifier classifier = new CheapLlmIntentClassifier(stub, 5_000);

        assertThat(classifier.classify("hi", Map.of()).label()).isEqualTo(IntentLabel.GREETING);
    }

    @Test
    void unknownLabelMappedToFallthrough() {
        StubLlm stub = StubLlm.respond("{\"intent\":\"UNKNOWN\",\"confidence\":0.4}");
        CheapLlmIntentClassifier classifier = new CheapLlmIntentClassifier(stub, 5_000);

        assertThat(classifier.classify("???", Map.of()).label()).isEqualTo(IntentLabel.FALLTHROUGH);
    }

    @Test
    void unparseableResponseFallsthroughInsteadOfThrowing() {
        StubLlm stub = StubLlm.respond("This is not JSON at all.");
        CheapLlmIntentClassifier classifier = new CheapLlmIntentClassifier(stub, 5_000);

        assertThat(classifier.classify("hi", Map.of()).label()).isEqualTo(IntentLabel.FALLTHROUGH);
    }

    @Test
    void timeoutFallsthroughBoundingTtfb() {
        StubLlm stub = StubLlm.slowResponding("{\"intent\":\"TIME_QUERY\",\"confidence\":0.9}", 600);
        // 50ms timeout — much shorter than the stub's 600ms delay.
        CheapLlmIntentClassifier classifier = new CheapLlmIntentClassifier(stub, 50);

        IntentResult result = classifier.classify("hi", Map.of());

        assertThat(result.label()).isEqualTo(IntentLabel.FALLTHROUGH);
    }

    @Test
    void runtimeExceptionFromCheapLlmFallsthroughNotPropagates() {
        StubLlm stub = StubLlm.alwaysFailing("simulated outage");
        CheapLlmIntentClassifier classifier = new CheapLlmIntentClassifier(stub, 5_000);

        assertThat(classifier.classify("hi", Map.of()).label()).isEqualTo(IntentLabel.FALLTHROUGH);
    }

    @Test
    void confidenceClampedTo01Range() {
        StubLlm stub = StubLlm.respond("{\"intent\":\"GREETING\",\"confidence\":1.7}");
        CheapLlmIntentClassifier classifier = new CheapLlmIntentClassifier(stub, 5_000);
        assertThat(classifier.classify("hi", Map.of()).confidence()).isEqualTo(1.0);
    }

    /** Minimal LlmRuntime stub with knobs for response/delay/exception. */
    private static final class StubLlm implements LlmRuntime {
        final AtomicInteger callCount = new AtomicInteger();
        private final String response;
        private final long delayMs;
        private final RuntimeException failure;

        private StubLlm(String response, long delayMs, RuntimeException failure) {
            this.response = response; this.delayMs = delayMs; this.failure = failure;
        }
        static StubLlm respond(String r) { return new StubLlm(r, 0, null); }
        static StubLlm slowResponding(String r, long delayMs) { return new StubLlm(r, delayMs, null); }
        static StubLlm alwaysFailing(String msg) { return new StubLlm(null, 0, new RuntimeException(msg)); }

        @Override
        public String generate(String s, List<String> m, Map<String, Object> meta) {
            callCount.incrementAndGet();
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (failure != null) throw failure;
            return response;
        }
        @Override public void stream(String s, List<String> m, Map<String, Object> meta,
                                     Consumer<String> onToken, Runnable onComplete) {
            throw new UnsupportedOperationException("not used");
        }
        @Override public ChatTurnResult generateWithTools(String s, List<String> m,
                                                          List<ToolSpecification> t, Map<String, Object> meta) {
            return ChatTurnResult.ofText(generate(s, m, meta));
        }
        @Override public ChatTurnResult generateChatTurn(String s, List<ChatMessage> m,
                                                         List<ToolSpecification> t, Map<String, Object> meta) {
            return ChatTurnResult.ofText(generate(s, List.of(), meta));
        }
    }
}
