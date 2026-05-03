package com.zhituagent.file;

import com.zhituagent.config.KafkaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Publishes {@link FileUploadEvent} to Kafka inside a transactional context.
 *
 * <p>{@code KafkaTemplate.executeInTransaction} wraps the send in a producer
 * transaction so consumers running with {@code isolation.level=read_committed}
 * never see a half-committed publish — paired with {@code acks=all +
 * enable.idempotence=true} this gives "exactly-once on the wire" for the
 * producer side (the consumer side is at-least-once but absorbed by ES
 * {@code _id=chunkId} idempotency).
 *
 * <p>Bean is conditional on Kafka being explicitly enabled so M2 sync mode
 * still boots when this layer is off.
 */
@Component
@ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "kafka-enabled", havingValue = "true")
public class FileUploadEventProducer {

    private static final Logger log = LoggerFactory.getLogger(FileUploadEventProducer.class);

    private final KafkaTemplate<String, Object> template;
    private final KafkaProperties props;

    public FileUploadEventProducer(KafkaTemplate<String, Object> template, KafkaProperties props) {
        this.template = Objects.requireNonNull(template);
        this.props = Objects.requireNonNull(props);
    }

    public void publish(FileUploadEvent event) {
        Objects.requireNonNull(event, "event");
        template.executeInTransaction(t -> {
            t.send(props.getTopicParse(), event.uploadId(), event);
            return null;
        });
        log.info("FileUploadEvent published uploadId={} sourceName={} objectKey={}",
                event.uploadId(), event.sourceName(), event.objectKey());
    }
}
