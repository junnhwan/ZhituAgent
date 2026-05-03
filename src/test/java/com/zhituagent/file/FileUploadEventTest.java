package com.zhituagent.file;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUploadEventTest {

    @Test
    void valid_event_round_trips_fields() {
        FileUploadEvent event = new FileUploadEvent("u-1", "doc.pdf", "uploads/u-1/doc.pdf", "application/pdf");
        assertThat(event.uploadId()).isEqualTo("u-1");
        assertThat(event.sourceName()).isEqualTo("doc.pdf");
        assertThat(event.objectKey()).isEqualTo("uploads/u-1/doc.pdf");
        assertThat(event.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void content_type_may_be_null_for_unknown_types() {
        FileUploadEvent event = new FileUploadEvent("u-1", "x", "k", null);
        assertThat(event.contentType()).isNull();
    }

    @Test
    void blank_required_fields_throw() {
        assertThatThrownBy(() -> new FileUploadEvent("", "s", "k", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileUploadEvent("u", "", "k", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileUploadEvent("u", "s", "", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileUploadEvent(null, "s", "k", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
