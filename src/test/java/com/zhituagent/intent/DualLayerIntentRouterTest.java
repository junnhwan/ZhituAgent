package com.zhituagent.intent;

import com.zhituagent.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DualLayerIntentRouterTest {

    @Test
    void ruleHitAtOrAboveSkipThresholdSkipsCheapLlmEntirely() {
        CountingClassifier cheap = new CountingClassifier(IntentResult.cheapLlm(IntentLabel.RAG_RETRIEVAL, 0.9, 5));
        RuleIntentClassifier rule = new RuleIntentClassifier(List.of());
        DualLayerIntentRouter router = newRouter(rule, cheap, intentProps(0.95, 0.6, 0.75));

        IntentResult result = router.classify("你好", Map.of());

        assertThat(result.label()).isEqualTo(IntentLabel.GREETING);
        assertThat(result.tier()).isEqualTo(IntentResult.Tier.RULE);
        assertThat(cheap.calls.get()).isZero();
    }

    @Test
    void ruleFallthroughTriggersCheapLlmTier() {
        CountingClassifier cheap = new CountingClassifier(
                IntentResult.cheapLlm(IntentLabel.RAG_RETRIEVAL, 0.85, 50)
        );
        DualLayerIntentRouter router = newRouter(
                new RuleIntentClassifier(List.of()), cheap, intentProps(0.95, 0.6, 0.75));

        IntentResult result = router.classify("ES native hybrid 检索原理", Map.of());

        assertThat(result.label()).isEqualTo(IntentLabel.RAG_RETRIEVAL);
        assertThat(result.tier()).isEqualTo(IntentResult.Tier.CHEAP_LLM);
        assertThat(cheap.calls.get()).isEqualTo(1);
    }

    @Test
    void cheapLlmConfidenceBelowThresholdFallsthrough() {
        CountingClassifier cheap = new CountingClassifier(
                IntentResult.cheapLlm(IntentLabel.RAG_RETRIEVAL, 0.5, 50)  // below 0.75 default
        );
        DualLayerIntentRouter router = newRouter(
                new RuleIntentClassifier(List.of()), cheap, intentProps(0.95, 0.6, 0.75));

        IntentResult result = router.classify("ambiguous query", Map.of());

        assertThat(result.label()).isEqualTo(IntentLabel.FALLTHROUGH);
        assertThat(cheap.calls.get()).isEqualTo(1);
    }

    @Test
    void cacheHitOnRepeatPromptDoesNotInvokeEitherClassifierAgain() {
        CountingClassifier cheap = new CountingClassifier(
                IntentResult.cheapLlm(IntentLabel.RAG_RETRIEVAL, 0.85, 50)
        );
        RuleIntentClassifier rule = new RuleIntentClassifier(List.of());
        DualLayerIntentRouter router = newRouter(rule, cheap, intentProps(0.95, 0.6, 0.75));

        router.classify("explain dense retrieval", Map.of());
        IntentResult second = router.classify("EXPLAIN dense retrieval", Map.of());  // case + space differs

        assertThat(second.tier()).isEqualTo(IntentResult.Tier.CACHE);
        assertThat(cheap.calls.get()).isEqualTo(1); // not 2
    }

    @Test
    void noCheapClassifierBeanResultsInRuleOnlyFallthrough() {
        DualLayerIntentRouter router = newRouter(
                new RuleIntentClassifier(List.of()), null, intentProps(0.95, 0.6, 0.75));

        IntentResult result = router.classify("ES native hybrid 检索原理", Map.of());

        assertThat(result.label()).isEqualTo(IntentLabel.FALLTHROUGH);
    }

    private static DualLayerIntentRouter newRouter(RuleIntentClassifier rule,
                                                   CheapLlmIntentClassifier cheap,
                                                   LlmProperties props) {
        return new DualLayerIntentRouter(rule, providerOf(cheap), props);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfAvailable(Supplier<T> defaultSupplier) {
                return value != null ? value : defaultSupplier.get();
            }
            @Override public T getIfUnique() { return value; }
            @Override public T getIfUnique(Supplier<T> defaultSupplier) {
                return value != null ? value : defaultSupplier.get();
            }
            @Override public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            @Override public Stream<T> orderedStream() { return stream(); }
        };
    }

    private static LlmProperties intentProps(double greetingTh, double ruleSkipTh, double cheapMinConf) {
        LlmProperties props = new LlmProperties();
        props.getIntent().setGreetingDirectAnswerThreshold(greetingTh);
        props.getIntent().setRuleConfidenceForSkipCheap(ruleSkipTh);
        props.getIntent().setCheapLlmConfidenceThreshold(cheapMinConf);
        return props;
    }

    /** Tracks call count and returns a fixed canned result. */
    private static final class CountingClassifier extends CheapLlmIntentClassifier {
        final AtomicInteger calls = new AtomicInteger();
        private final IntentResult fixed;

        CountingClassifier(IntentResult fixed) {
            super(new NoOpLlm(), 1_000);
            this.fixed = fixed;
        }

        @Override
        public IntentResult classify(String userMessage, Map<String, Object> sessionMetadata) {
            calls.incrementAndGet();
            return fixed;
        }

        private static final class NoOpLlm implements com.zhituagent.llm.LlmRuntime {
            @Override public String generate(String s, List<String> m, Map<String, Object> meta) { return ""; }
            @Override public void stream(String s, List<String> m, Map<String, Object> meta,
                                         Consumer<String> onToken, Runnable onComplete) { onComplete.run(); }
        }
    }

    @SuppressWarnings("unused")
    private static final Function<String, String> IDENT = Function.identity();
}
