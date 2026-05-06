package com.zhituagent.config;

import com.zhituagent.llm.LangChain4jLlmRuntime;
import com.zhituagent.llm.LlmRateLimiter;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.llm.RoutingLlmRuntime;
import com.zhituagent.metrics.AiMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the primary/fallback {@link com.zhituagent.llm.LangChain4jLlmRuntime}
 * pair plus the {@link com.zhituagent.llm.RoutingLlmRuntime} decorator when
 * {@code zhitu.llm.router.enabled=true}.
 *
 * <p>When the property is false (default), this whole {@code @Configuration} is
 * skipped and the original {@code @Service} on {@code LangChain4jLlmRuntime}
 * (gated by {@code matchIfMissing=true}) provides the single bean as before —
 * keeping the pre-router baseline byte-stable.
 *
 * <p>Both tier beans share the top-level {@code zhitu.llm.baseUrl} /
 * {@code apiKey} and only differ on {@code modelName}. When a tier's modelName
 * is left empty in config it transparently falls through to
 * {@code zhitu.llm.modelName} via {@code LangChain4jLlmRuntime.effectiveModelName()}.
 *
 * <p>Naming convention:
 * <ul>
 *   <li>{@code primaryLlm} — the expensive default model (e.g. gpt-5.4)
 *   <li>{@code fallbackLlm} — the cheap backup (e.g. gpt-5.4-mini); also
 *       reused by M1 dual-layer intent classifier and M3 reflection scorer
 *       so we only pay for one mini bean across the whole app.
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "zhitu.llm.router", name = "enabled", havingValue = "true")
public class ModelRouterConfig {

    @Bean("primaryLlm")
    public LangChain4jLlmRuntime primaryLlm(LlmProperties properties,
                                            AiMetricsRecorder aiMetricsRecorder,
                                            LlmRateLimiter rateLimiter) {
        return new LangChain4jLlmRuntime(
                properties,
                aiMetricsRecorder,
                rateLimiter,
                properties.getRouter().getPrimary().getModelName()
        );
    }

    @Bean("fallbackLlm")
    public LangChain4jLlmRuntime fallbackLlm(LlmProperties properties,
                                             AiMetricsRecorder aiMetricsRecorder,
                                             LlmRateLimiter rateLimiter) {
        return new LangChain4jLlmRuntime(
                properties,
                aiMetricsRecorder,
                rateLimiter,
                properties.getRouter().getFallback().getModelName()
        );
    }

    @Bean
    @Primary
    public LlmRuntime routingLlmRuntime(@Qualifier("primaryLlm") LangChain4jLlmRuntime primary,
                                        @Qualifier("fallbackLlm") LangChain4jLlmRuntime fallback,
                                        LlmProperties properties,
                                        ObjectProvider<MeterRegistry> meterRegistry) {
        return new RoutingLlmRuntime(
                primary,
                fallback,
                properties.getRouter().getCircuitBreaker(),
                meterRegistry.getIfAvailable()
        );
    }
}
