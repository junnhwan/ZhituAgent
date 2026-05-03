package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka KRaft single-node configuration for the v3 async file pipeline.
 *
 * <p>Producer publishes {@code FileUploadEvent} after a MinIO put succeeds; the
 * controller returns 202 immediately. Consumer drives Tika parse + embedding +
 * ES bulkIndex on a separate thread pool. {@code chunkId} (sha256 of content)
 * becomes the ES {@code _id} so at-least-once redelivery yields exactly-once
 * effect — Kafka transactions are NOT used on the consumer side because the
 * downstream side-effect (ES write) is not Kafka-backed.
 */
@ConfigurationProperties(prefix = "zhitu.kafka")
public class KafkaProperties {

    private String bootstrapServers = "localhost:9092";
    private String topicParse = "zhitu.file.parse";
    private String topicDlt = "zhitu.file.parse.DLT";
    private String consumerGroup = "zhitu-file-parse";
    private String transactionalIdPrefix = "zhitu-file-tx-";
    private long retryBackoffMs = 3000L;
    private long retryMaxAttempts = 4L;

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTopicParse() {
        return topicParse;
    }

    public void setTopicParse(String topicParse) {
        this.topicParse = topicParse;
    }

    public String getTopicDlt() {
        return topicDlt;
    }

    public void setTopicDlt(String topicDlt) {
        this.topicDlt = topicDlt;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getTransactionalIdPrefix() {
        return transactionalIdPrefix;
    }

    public void setTransactionalIdPrefix(String transactionalIdPrefix) {
        this.transactionalIdPrefix = transactionalIdPrefix;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public long getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(long retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }
}
