package com.zhituagent.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.mcp")
public class McpProperties {

    private boolean enabled = false;
    /** {@code mock} | {@code stdio} | {@code sse}. Only {@code mock} is implemented today. */
    private String transport = "mock";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }
}
