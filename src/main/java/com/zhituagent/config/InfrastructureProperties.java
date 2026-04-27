package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.infrastructure")
public class InfrastructureProperties {

    private boolean redisEnabled = false;
    private boolean pgvectorEnabled = false;

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public boolean isPgvectorEnabled() {
        return pgvectorEnabled;
    }

    public void setPgvectorEnabled(boolean pgvectorEnabled) {
        this.pgvectorEnabled = pgvectorEnabled;
    }
}
