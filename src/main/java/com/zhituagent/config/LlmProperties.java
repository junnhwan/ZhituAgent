package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.llm")
public class LlmProperties {

    private boolean mockMode = true;
    private String baseUrl = "";
    private String apiKey = "";
    private String modelName = "mock-agent";
    private final RateLimit rateLimit = new RateLimit();
    private final Router router = new Router();
    private final Intent intent = new Intent();

    public boolean isMockMode() {
        return mockMode;
    }

    public void setMockMode(boolean mockMode) {
        this.mockMode = mockMode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Router getRouter() {
        return router;
    }

    public Intent getIntent() {
        return intent;
    }

    public static class RateLimit {

        private boolean enabled = false;
        private int limitForPeriod = 48;
        private long limitRefreshPeriodSeconds = 60;
        private long timeoutSeconds = 120;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLimitForPeriod() {
            return limitForPeriod;
        }

        public void setLimitForPeriod(int limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
        }

        public long getLimitRefreshPeriodSeconds() {
            return limitRefreshPeriodSeconds;
        }

        public void setLimitRefreshPeriodSeconds(long limitRefreshPeriodSeconds) {
            this.limitRefreshPeriodSeconds = limitRefreshPeriodSeconds;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /**
     * Primary/fallback model routing with circuit breaker (M2 borrow from project-java).
     *
     * <p>When {@code enabled=false} (default) the existing single-bean {@link
     * com.zhituagent.llm.LangChain4jLlmRuntime @Service} stays active and the
     * application behaves identically to the pre-router baseline. When enabled,
     * {@code com.zhituagent.config.ModelRouterConfig} produces two named runtimes
     * ({@code primaryLlm} / {@code fallbackLlm}) wrapped by a {@code @Primary
     * RoutingLlmRuntime} decorator that owns two Resilience4j CircuitBreakers.
     *
     * <p>Both tiers share the top-level {@code zhitu.llm.baseUrl} / {@code apiKey}
     * — only {@code modelName} differs. Typical setup: primary=gpt-5.4,
     * fallback=gpt-5.4-mini (same family, cheaper). The mini bean is also
     * reused as the cheap classifier in M1 dual-layer intent and as the
     * scorer in M3 reflection.
     */
    public static class Router {

        private boolean enabled = false;
        private final Tier primary = new Tier();
        private final Tier fallback = new Tier();
        private final CircuitBreaker circuitBreaker = new CircuitBreaker();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Tier getPrimary() {
            return primary;
        }

        public Tier getFallback() {
            return fallback;
        }

        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }

        public static class Tier {

            /** Empty string falls back to the top-level {@code zhitu.llm.modelName}. */
            private String modelName = "";

            public String getModelName() {
                return modelName;
            }

            public void setModelName(String modelName) {
                this.modelName = modelName;
            }
        }

        /**
         * Resilience4j CircuitBreaker thresholds. Two CB instances are built —
         * one for {@code llm-primary}, one for {@code llm-fallback} — sharing
         * this config. Defaults are tuned for LLM call latencies (8s slow-call
         * threshold accounts for token-by-token generation).
         */
        public static class CircuitBreaker {

            private int failureRateThreshold = 50;
            private long slowCallDurationThresholdMs = 8000;
            private int slowCallRateThreshold = 80;
            private int minimumNumberOfCalls = 10;
            private int slidingWindowSize = 20;
            private long waitDurationInOpenStateMs = 30000;
            private int permittedCallsInHalfOpenState = 3;

            public int getFailureRateThreshold() {
                return failureRateThreshold;
            }

            public void setFailureRateThreshold(int failureRateThreshold) {
                this.failureRateThreshold = failureRateThreshold;
            }

            public long getSlowCallDurationThresholdMs() {
                return slowCallDurationThresholdMs;
            }

            public void setSlowCallDurationThresholdMs(long slowCallDurationThresholdMs) {
                this.slowCallDurationThresholdMs = slowCallDurationThresholdMs;
            }

            public int getSlowCallRateThreshold() {
                return slowCallRateThreshold;
            }

            public void setSlowCallRateThreshold(int slowCallRateThreshold) {
                this.slowCallRateThreshold = slowCallRateThreshold;
            }

            public int getMinimumNumberOfCalls() {
                return minimumNumberOfCalls;
            }

            public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
                this.minimumNumberOfCalls = minimumNumberOfCalls;
            }

            public int getSlidingWindowSize() {
                return slidingWindowSize;
            }

            public void setSlidingWindowSize(int slidingWindowSize) {
                this.slidingWindowSize = slidingWindowSize;
            }

            public long getWaitDurationInOpenStateMs() {
                return waitDurationInOpenStateMs;
            }

            public void setWaitDurationInOpenStateMs(long waitDurationInOpenStateMs) {
                this.waitDurationInOpenStateMs = waitDurationInOpenStateMs;
            }

            public int getPermittedCallsInHalfOpenState() {
                return permittedCallsInHalfOpenState;
            }

            public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
                this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
            }
        }
    }

    /**
     * Dual-layer intent recognition (M1 borrow from project-java).
     *
     * <p>When {@code dualLayer.enabled=true}, the {@link
     * com.zhituagent.intent.DualLayerIntentRouter} runs <i>before</i> RAG and
     * tool-selection in {@link com.zhituagent.orchestrator.AgentOrchestrator}
     * to short-circuit obvious cases:
     *
     * <ul>
     *   <li>Greetings → direct answer with no LLM call.
     *   <li>Time/date queries → skip RAG, go straight to tool selection.
     *   <li>Anything else uncertain → fall through to existing logic.
     * </ul>
     *
     * <p>The cheap-LLM tier reuses the same {@code @Qualifier("fallbackLlm")}
     * bean produced by {@link Router}, so enabling M1 normally implies
     * {@code zhitu.llm.router.enabled=true} too. With router disabled, only
     * the rule tier is active.
     */
    public static class Intent {

        private final DualLayer dualLayer = new DualLayer();
        /** Confidence threshold a cheap-LLM result must meet to be trusted; below → fallthrough. */
        private double cheapLlmConfidenceThreshold = 0.75;
        /** Hard timeout for the cheap-LLM call. On timeout → fallthrough, bounding TTFB impact. */
        private long cheapLlmTimeoutMs = 800;
        /** Rule confidence at or above which the cheap-LLM tier is skipped entirely. */
        private double ruleConfidenceForSkipCheap = 0.6;
        /** LRU cache size for resolved intents (keyed by sha256 of normalized prompt). */
        private int cacheMaxEntries = 1024;
        /** Cache TTL in milliseconds. 0 disables expiry. */
        private long cacheTtlMs = 5 * 60 * 1000;
        /**
         * Confidence at or above which a {@code GREETING} label triggers
         * {@code RouteDecision.direct()} with zero LLM cost. Default 0.95
         * — high enough that random text never matches.
         */
        private double greetingDirectAnswerThreshold = 0.95;
        /** Operator-supplied rules appended after the built-in defaults. */
        private java.util.List<Rule> rules = new java.util.ArrayList<>();

        public DualLayer getDualLayer() { return dualLayer; }

        public double getCheapLlmConfidenceThreshold() { return cheapLlmConfidenceThreshold; }
        public void setCheapLlmConfidenceThreshold(double v) { this.cheapLlmConfidenceThreshold = v; }

        public long getCheapLlmTimeoutMs() { return cheapLlmTimeoutMs; }
        public void setCheapLlmTimeoutMs(long v) { this.cheapLlmTimeoutMs = v; }

        public double getRuleConfidenceForSkipCheap() { return ruleConfidenceForSkipCheap; }
        public void setRuleConfidenceForSkipCheap(double v) { this.ruleConfidenceForSkipCheap = v; }

        public int getCacheMaxEntries() { return cacheMaxEntries; }
        public void setCacheMaxEntries(int v) { this.cacheMaxEntries = v; }

        public long getCacheTtlMs() { return cacheTtlMs; }
        public void setCacheTtlMs(long v) { this.cacheTtlMs = v; }

        public double getGreetingDirectAnswerThreshold() { return greetingDirectAnswerThreshold; }
        public void setGreetingDirectAnswerThreshold(double v) { this.greetingDirectAnswerThreshold = v; }

        public java.util.List<Rule> getRules() { return rules; }
        public void setRules(java.util.List<Rule> rules) { this.rules = rules; }

        /**
         * Wrapper namespace so {@code zhitu.llm.intent.dual-layer.enabled} is
         * a clean property key consumable by {@code @ConditionalOnProperty}.
         */
        public static class DualLayer {
            private boolean enabled = false;
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
        }

        /** Single regex rule contributed via configuration. */
        public static class Rule {
            private String name;
            private String pattern;
            /** Must match {@link com.zhituagent.intent.IntentLabel} value. */
            private String label;
            private double confidence = 0.9;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getPattern() { return pattern; }
            public void setPattern(String pattern) { this.pattern = pattern; }
            public String getLabel() { return label; }
            public void setLabel(String label) { this.label = label; }
            public double getConfidence() { return confidence; }
            public void setConfidence(double confidence) { this.confidence = confidence; }
        }
    }
}
