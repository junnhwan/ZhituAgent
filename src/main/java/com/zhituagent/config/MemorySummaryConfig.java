package com.zhituagent.config;

import com.zhituagent.llm.LangChain4jLlmRuntime;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.memory.ConversationSummarizer;
import com.zhituagent.memory.LlmConversationSummarizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MemorySummaryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.memory.summary", name = "enabled", havingValue = "true")
    ConversationSummarizer memoryConversationSummarizer(
            @Qualifier("fallbackLlm") ObjectProvider<LlmRuntime> fallbackProvider,
            ObjectProvider<LlmRuntime> primaryProvider,
            LlmProperties llmProperties,
            MemorySummaryProperties summaryProperties) {
        LlmRuntime fallback = fallbackProvider.getIfAvailable();
        LlmRuntime selected = fallback != null ? fallback : primaryProvider.getIfAvailable();
        if (selected == null || llmProperties.isMockMode()) {
            return (previousSummary, messagesToCompress) -> com.zhituagent.memory.SummaryResult.disabled();
        }
        return new LlmConversationSummarizer(selected, modelNameOf(selected, llmProperties), summaryProperties);
    }

    private String modelNameOf(LlmRuntime runtime, LlmProperties llmProperties) {
        if (runtime instanceof LangChain4jLlmRuntime langChain4jLlmRuntime) {
            return langChain4jLlmRuntime.effectiveModelName();
        }
        String fallbackModelName = llmProperties.getRouter().getFallback().getModelName();
        if (fallbackModelName != null && !fallbackModelName.isBlank()) {
            return fallbackModelName;
        }
        return llmProperties.getModelName();
    }
}
