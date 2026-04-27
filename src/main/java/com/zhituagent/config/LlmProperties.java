package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.llm")
public class LlmProperties {

    private boolean mockMode = true;
    private String baseUrl = "";
    private String apiKey = "";
    private String modelName = "mock-agent";

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
}
