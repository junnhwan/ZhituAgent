package com.zhituagent.config;

import com.zhituagent.ZhituAgentApplication;
import com.zhituagent.llm.LangChain4jLlmRuntime;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.llm.RoutingLlmRuntime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code @ConditionalOnProperty} + {@code @Primary} wiring of
 * {@link ModelRouterConfig}: when the router is off, the existing single
 * {@link LangChain4jLlmRuntime} {@code @Service} bean is used directly; when
 * on, the {@link RoutingLlmRuntime} decorator becomes the {@code @Primary}
 * resolution and two named tier beans coexist.
 */
class ModelRouterWiringTest {

    @Nested
    @SpringBootTest(
            classes = ZhituAgentApplication.class,
            properties = {
                    "zhitu.infrastructure.redis-enabled=false",
                    "zhitu.llm.router.enabled=false"
            }
    )
    class RouterDisabled {

        @Autowired
        private LlmRuntime llmRuntime;

        @Test
        void primaryBeanIsTheOriginalLangChain4jRuntime() {
            assertThat(llmRuntime).isInstanceOf(LangChain4jLlmRuntime.class);
        }
    }

    @Nested
    @SpringBootTest(
            classes = ZhituAgentApplication.class,
            properties = {
                    "zhitu.infrastructure.redis-enabled=false",
                    "zhitu.llm.router.enabled=true",
                    "zhitu.llm.router.primary.modelName=gpt-5.4",
                    "zhitu.llm.router.fallback.modelName=gpt-5.4-mini"
            }
    )
    class RouterEnabled {

        @Autowired
        private LlmRuntime llmRuntime;

        @Autowired
        @Qualifier("primaryLlm")
        private LangChain4jLlmRuntime primaryLlm;

        @Autowired
        @Qualifier("fallbackLlm")
        private LangChain4jLlmRuntime fallbackLlm;

        @Test
        void primaryBeanIsRoutingDecoratorAndTierBeansHaveDistinctModelNames() {
            assertThat(llmRuntime).isInstanceOf(RoutingLlmRuntime.class);
            assertThat(primaryLlm.effectiveModelName()).isEqualTo("gpt-5.4");
            assertThat(fallbackLlm.effectiveModelName()).isEqualTo("gpt-5.4-mini");
        }
    }
}
