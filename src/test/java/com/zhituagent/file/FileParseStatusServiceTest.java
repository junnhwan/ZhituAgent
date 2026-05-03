package com.zhituagent.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileParseStatusServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private FileParseStatusService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new FileParseStatusService(redis);
    }

    @Test
    void mark_writes_status_with_24h_ttl() {
        service.mark("u-1", FileParseStatusService.Status.QUEUED);
        verify(ops).set(eq("zhitu:upload:status:u-1"), eq("QUEUED"), eq(Duration.ofHours(24)));
    }

    @Test
    void get_decodes_known_status() {
        when(ops.get("zhitu:upload:status:u-9")).thenReturn("INDEXED");
        assertThat(service.get("u-9")).isEqualTo(FileParseStatusService.Status.INDEXED);
    }

    @Test
    void get_returns_null_for_unknown_or_missing_keys() {
        when(ops.get(any())).thenReturn(null);
        assertThat(service.get("nope")).isNull();

        when(ops.get(any())).thenReturn("UNKNOWN_STATE");
        assertThat(service.get("nope")).isNull();
    }

    @Test
    void blank_uploadId_throws_on_mark() {
        assertThatThrownBy(() -> service.mark("", FileParseStatusService.Status.QUEUED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_uploadId_returns_null_on_get() {
        assertThat(service.get(null)).isNull();
        assertThat(service.get("")).isNull();
    }
}
