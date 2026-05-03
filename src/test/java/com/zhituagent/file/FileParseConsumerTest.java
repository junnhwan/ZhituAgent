package com.zhituagent.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileParseConsumerTest {

    private FileIngestService ingest;
    private FileParseStatusService status;
    private FileParseConsumer consumer;

    @BeforeEach
    void setUp() {
        ingest = mock(FileIngestService.class);
        status = mock(FileParseStatusService.class);
        consumer = new FileParseConsumer(ingest, status);
    }

    @Test
    void onEvent_marks_parsing_then_indexed_on_success() {
        FileUploadEvent event = new FileUploadEvent("u-1", "x.pdf", "uploads/u-1/x.pdf", "application/pdf");
        when(ingest.ingestFromMinio("uploads/u-1/x.pdf", "x.pdf", "application/pdf")).thenReturn(7);

        consumer.onEvent(event);

        InOrder ord = inOrder(status, ingest);
        ord.verify(status).mark("u-1", FileParseStatusService.Status.PARSING);
        ord.verify(ingest).ingestFromMinio("uploads/u-1/x.pdf", "x.pdf", "application/pdf");
        ord.verify(status).mark("u-1", FileParseStatusService.Status.INDEXED);
    }

    @Test
    void onEvent_marks_failed_then_rethrows_so_kafka_error_handler_can_retry() {
        FileUploadEvent event = new FileUploadEvent("u-2", "y.pdf", "uploads/u-2/y.pdf", "application/pdf");
        doThrow(new RuntimeException("tika boom")).when(ingest).ingestFromMinio(eq("uploads/u-2/y.pdf"), eq("y.pdf"), eq("application/pdf"));

        assertThatThrownBy(() -> consumer.onEvent(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("tika boom");
        verify(status).mark("u-2", FileParseStatusService.Status.PARSING);
        verify(status).mark("u-2", FileParseStatusService.Status.FAILED);
        verify(status, never()).mark("u-2", FileParseStatusService.Status.INDEXED);
    }

    @Test
    void onEvent_null_event_is_no_op_to_avoid_NPE_on_DLQ_path() {
        consumer.onEvent(null);
        verifyNoInteractions(ingest, status);
    }

    @Test
    void redelivery_of_same_event_calls_ingest_again_idempotency_lives_in_es_id() {
        FileUploadEvent event = new FileUploadEvent("u-3", "z.pdf", "uploads/u-3/z.pdf", "application/pdf");
        when(ingest.ingestFromMinio(eq("uploads/u-3/z.pdf"), eq("z.pdf"), eq("application/pdf"))).thenReturn(3);

        consumer.onEvent(event);
        consumer.onEvent(event);

        verify(ingest, times(2)).ingestFromMinio("uploads/u-3/z.pdf", "z.pdf", "application/pdf");
    }
}
