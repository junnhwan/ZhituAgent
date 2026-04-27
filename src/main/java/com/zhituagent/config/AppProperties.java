package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.app")
public class AppProperties {

    private String systemPromptLocation = "classpath:system-prompt/chat-agent.txt";

    public String getSystemPromptLocation() {
        return systemPromptLocation;
    }

    public void setSystemPromptLocation(String systemPromptLocation) {
        this.systemPromptLocation = systemPromptLocation;
    }
}
