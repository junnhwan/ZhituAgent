package com.zhituagent.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Async consumer for {@link FileUploadEvent}. Drives Tika parse + embedding +
 * ES bulkIndex on the consumer thread pool, freeing the upload HTTP thread to
 * return 202 in &lt; 200 ms even for big files.
 *
 * <p><b>At-least-once + idempotent absorption</b>: the consumer does NOT enroll
 * in a Kafka tx (its side-effect is an ES write, not a Kafka write — mixing
 * them only buys grief). On retry/redelivery, {@code KnowledgeStoreIds.computeChunkId}
 * (sha256 of content) collapses re-published rows into the same ES {@code _id},
 * so duplicate deliveries become UPSERTs that no-op.
 *
 * <p><b>Retry &amp; DLT</b>: orchestrated by the
 * {@code DefaultErrorHandler + DeadLetterPublishingRecoverer + FixedBackOff}
 * chain wired in {@code KafkaConfig}. After {@code retry-max-attempts}
 * exhausted, the original record is published to {@code zhitu.file.parse.DLT}
 * for human triage.
 */
@Component
@ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "kafka-enabled", havingValue = "true")
public class FileParseConsumer {

    private static final Logger log = LoggerFactory.getLogger(FileParseConsumer.class);

    private final FileIngestService ingestService;
    private final FileParseStatusService statusService;

    public FileParseConsumer(FileIngestService ingestService, FileParseStatusService statusService) {
        this.ingestService = Objects.requireNonNull(ingestService);
        this.statusService = Objects.requireNonNull(statusService);
    }

    @KafkaListener(topics = "${zhitu.kafka.topic-parse}", containerFactory = "kafkaListenerContainerFactory")
    public void onEvent(FileUploadEvent event) {
        if (event == null) {
            log.warn("Null event received, skipping");
            return;
        }
        log.info("Async parse start uploadId={} sourceName={} objectKey={}",
                event.uploadId(), event.sourceName(), event.objectKey());
        try {
            statusService.mark(event.uploadId(), FileParseStatusService.Status.PARSING);
            int chunks = ingestService.ingestFromMinio(
                    event.objectKey(), event.sourceName(), event.contentType());
            statusService.mark(event.uploadId(), FileParseStatusService.Status.INDEXED);
            log.info("Async parse done uploadId={} sourceName={} chunks={}",
                    event.uploadId(), event.sourceName(), chunks);
        } catch (Exception ex) {
            log.error("Async parse failed uploadId={} sourceName={} cause={}",
                    event.uploadId(), event.sourceName(), ex.toString(), ex);
            statusService.mark(event.uploadId(), FileParseStatusService.Status.FAILED);
            throw ex;
        }
    }
}
