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
}
