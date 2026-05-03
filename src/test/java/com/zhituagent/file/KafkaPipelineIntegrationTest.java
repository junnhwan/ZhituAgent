package com.zhituagent.file;

import com.zhituagent.config.KafkaConfig;
import com.zhituagent.config.KafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end Kafka pipeline test against Testcontainers KRaft Kafka. Exercises
 * the full chain: {@link FileUploadEventProducer} executeInTransaction →
 * Kafka broker → {@link FileParseConsumer} → mocked {@link FileIngestService}.
 *
 * <p>Bundles four M3 acceptance scenarios into one container start (cheaper
 * than splitting into 4 classes that each pay container boot cost):
 * happy-path delivery, retry-then-success, DLT on persistent failure, and
 * idempotent redelivery (the consumer is invoked twice — idempotency lives
 * downstream in ES {@code _id=chunkId} and is asserted in unit tests).
 *
 * <p>Each test uses a unique {@code uploadId} so consumer-group offset state
 * doesn't bleed across tests; mocks reset between tests via {@link AfterEach}.
 * No {@code @DirtiesContext} — keeping the same producer factory across tests
 * avoids transactional-id-prefix epoch collisions on the broker side.
 */
@Tag("integration")
@SpringBootTest(classes = KafkaPipelineIntegrationTest.TestConfig.class,
        properties = {
                "zhitu.infrastructure.kafka-enabled=true",
                "zhitu.infrastructure.minio-enabled=false",
                "zhitu.infrastructure.elasticsearch-enabled=false",
                "zhitu.infrastructure.redis-enabled=false",
                "zhitu.kafka.consumer-group=zhitu-file-parse-it",
                "zhitu.kafka.retry-backoff-ms=200",
                "zhitu.kafka.retry-max-attempts=2"
        })
@Testcontainers(disabledWithoutDocker = true)
class KafkaPipelineIntegrationTest {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void registerKafka(DynamicPropertyRegistry reg) {
        reg.add("zhitu.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired FileUploadEventProducer producer;
    @Autowired KafkaProperties props;
    @Autowired KafkaListenerEndpointRegistry registry;
    @MockBean FileIngestService ingestService;
    @MockBean FileParseStatusService statusService;

    @BeforeEach
    void waitForListeners() {
        registry.getListenerContainers().forEach(c -> ContainerTestUtils.waitForAssignment(c, 1));
    }

    @AfterEach
    void resetMocks() {
        org.mockito.Mockito.reset(ingestService, statusService);
    }

    @Test
    void happy_path_event_publish_drives_consumer_through_ingest() {
        when(ingestService.ingestFromMinio(anyString(), anyString(), anyString())).thenReturn(3);
        FileUploadEvent event = new FileUploadEvent("ut-happy", "ok.pdf", "uploads/ut-happy/ok.pdf", "application/pdf");

        producer.publish(event);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> verify(ingestService).ingestFromMinio(
                        "uploads/ut-happy/ok.pdf", "ok.pdf", "application/pdf"));
        verify(statusService).mark("ut-happy", FileParseStatusService.Status.PARSING);
        verify(statusService).mark("ut-happy", FileParseStatusService.Status.INDEXED);
    }

    @Test
    void retry_then_success_with_initial_plus_retries_attempts() {
        AtomicInteger attempts = new AtomicInteger();
        when(ingestService.ingestFromMinio(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    if (attempts.incrementAndGet() < 2) {
                        throw new RuntimeException("transient embedding failure attempt=" + attempts.get());
                    }
                    return 5;
                });

        producer.publish(new FileUploadEvent("ut-retry", "z.pdf", "uploads/ut-retry/z.pdf", "application/pdf"));

        await().atMost(Duration.ofSeconds(25))
                .untilAsserted(() -> assertThat(attempts.get()).isGreaterThanOrEqualTo(2));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> verify(statusService).mark("ut-retry", FileParseStatusService.Status.INDEXED));
    }

    @Test
    void persistent_failure_routes_to_DLT_after_retries_exhausted() {
        when(ingestService.ingestFromMinio(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("permanent: bogus minio key"));

        producer.publish(new FileUploadEvent("ut-dlt", "bad.pdf", "uploads/ut-dlt/bad.pdf", "application/pdf"));

        ConsumerRecord<?, ?> dltRecord = KafkaTestUtils.getOneRecord(
                kafka.getBootstrapServers(),
                "zhitu-dlt-verify-" + System.nanoTime(),
                props.getTopicDlt(),
                0, false, true, Duration.ofSeconds(30));
        assertThat(dltRecord).isNotNull();
        assertThat(String.valueOf(dltRecord.value())).contains("ut-dlt").contains("bad.pdf");
        verify(ingestService, atLeast(2)).ingestFromMinio(anyString(), anyString(), anyString());
    }

    @Test
    void idempotent_redelivery_consumer_processes_twice_es_id_collapses_downstream() {
        when(ingestService.ingestFromMinio(anyString(), anyString(), anyString())).thenReturn(2);
        FileUploadEvent event = new FileUploadEvent("ut-idemp", "r.pdf", "uploads/ut-idemp/r.pdf", "application/pdf");

        producer.publish(event);
        producer.publish(event);

        await().atMost(Duration.ofSeconds(25))
                .untilAsserted(() -> verify(ingestService, times(2)).ingestFromMinio(
                        "uploads/ut-idemp/r.pdf", "r.pdf", "application/pdf"));
    }

    /**
     * Minimal Spring Boot context: only Kafka pieces wired. MinIO/ES/Redis all
     * gated off so their @Component beans never instantiate. Component scan
     * starts at this class's package ({@code com.zhituagent.file}) so the real
     * {@link FileUploadEventProducer} and {@link FileParseConsumer} components
     * are picked up; their gated dependencies (FileIngestService,
     * FileParseStatusService) are supplied by {@code @MockBean}.
     */
    @org.springframework.boot.autoconfigure.SpringBootApplication(exclude = {
            org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
            org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class
    })
    @Import({KafkaConfig.class})
    static class TestConfig {

        @org.springframework.context.annotation.Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }
}
