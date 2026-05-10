package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.memory.summary")
public class MemorySummaryProperties {

    private boolean enabled = false;
    private int triggerMessageCount = 6;
    private int maxRecentMessages = 4;
    private long timeoutMillis = 1500;
    private int maxOutputChars = 1200;
    private boolean fallbackOnFailure = true;
    private String modelPurpose = "memory-summary";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTriggerMessageCount() {
        return triggerMessageCount;
    }

    public void setTriggerMessageCount(int triggerMessageCount) {
        this.triggerMessageCount = triggerMessageCount;
    }

    public int getMaxRecentMessages() {
        return maxRecentMessages;
    }

    public void setMaxRecentMessages(int maxRecentMessages) {
        this.maxRecentMessages = maxRecentMessages;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public boolean isFallbackOnFailure() {
        return fallbackOnFailure;
    }

    public void setFallbackOnFailure(boolean fallbackOnFailure) {
        this.fallbackOnFailure = fallbackOnFailure;
    }

    public String getModelPurpose() {
        return modelPurpose;
    }

    public void setModelPurpose(String modelPurpose) {
        this.modelPurpose = modelPurpose;
    }
}
