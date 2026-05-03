package com.zhituagent.file;

import com.zhituagent.config.KafkaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class FileUploadEventProducerTest {

    private KafkaTemplate<String, Object> template;
    private KafkaProperties props;
    private FileUploadEventProducer producer;

    @BeforeEach
    void setUp() {
        template = mock(KafkaTemplate.class);
        props = new KafkaProperties();
        producer = new FileUploadEventProducer(template, props);

        // executeInTransaction(callback) → invoke the callback with the same template
        // (good enough for unit assertion of "send was called inside a tx scope").
        when(template.executeInTransaction(any()))
                .thenAnswer(inv -> {
                    KafkaOperations.OperationsCallback<String, Object, Object> cb = inv.getArgument(0);
                    return cb.doInOperations(template);
                });
    }

    @Test
    void publish_sends_event_to_configured_topic_under_tx() {
        FileUploadEvent event = new FileUploadEvent("u-1", "doc.pdf", "uploads/u-1/doc.pdf", "application/pdf");

        producer.publish(event);

        verify(template, times(1)).executeInTransaction(any());
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(template).send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo(props.getTopicParse());
        assertThat(keyCaptor.getValue()).isEqualTo("u-1");
        assertThat(valueCaptor.getValue()).isEqualTo(event);
    }
}
