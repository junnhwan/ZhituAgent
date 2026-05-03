package com.zhituagent.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkedUploadServiceTest {

    private static final String UPLOAD_ID = "user-1:abc123md5";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    /** In-memory bitmap simulator so tests can model SETBIT idempotence. */
    private Map<String, Set<Long>> bitmaps;
    private ChunkedUploadService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        bitmaps = new HashMap<>();
        // SETBIT: write through to in-memory simulator, return previous bit
        when(ops.setBit(any(String.class), anyLong(), anyBoolean())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            long off = inv.getArgument(1);
            boolean v = inv.getArgument(2);
            Set<Long> bits = bitmaps.computeIfAbsent(k, x -> new HashSet<>());
            boolean prev = bits.contains(off);
            if (v) bits.add(off);
            else bits.remove(off);
            return prev;
        });
        // GETBIT: read from simulator
        when(ops.getBit(any(String.class), anyLong())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            long off = inv.getArgument(1);
            return bitmaps.getOrDefault(k, Set.of()).contains(off);
        });
        // expire/delete simple stubs
        when(redis.expire(any(String.class), any(Duration.class))).thenReturn(true);
        when(redis.delete(any(String.class))).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            return bitmaps.remove(k) != null;
        });

        service = new ChunkedUploadService(redis);
    }

    @Test
    void mark_then_check_returns_true_for_uploaded_chunk() {
        service.markChunkUploaded(UPLOAD_ID, 3);

        assertThat(service.isChunkUploaded(UPLOAD_ID, 3)).isTrue();
        assertThat(service.isChunkUploaded(UPLOAD_ID, 4)).isFalse();
    }

    @Test
    void marking_same_chunk_twice_is_idempotent_bitmap_unchanged() {
        service.markChunkUploaded(UPLOAD_ID, 0);
        service.markChunkUploaded(UPLOAD_ID, 0); // re-mark
        service.markChunkUploaded(UPLOAD_ID, 0); // re-mark again

        assertThat(service.isChunkUploaded(UPLOAD_ID, 0)).isTrue();
        // Bitmap snapshot should still be a single bit, not three.
        assertThat(bitmaps.get("zhitu:upload:" + UPLOAD_ID)).containsExactly(0L);
    }

    @Test
    void mark_refreshes_ttl_each_time() {
        service.markChunkUploaded(UPLOAD_ID, 1);
        service.markChunkUploaded(UPLOAD_ID, 2);
        service.markChunkUploaded(UPLOAD_ID, 3);

        verify(redis, times(3)).expire(eq("zhitu:upload:" + UPLOAD_ID), eq(Duration.ofHours(24)));
    }

    @Test
    void getUploadedChunks_returns_in_order() {
        service.markChunkUploaded(UPLOAD_ID, 2);
        service.markChunkUploaded(UPLOAD_ID, 0);
        service.markChunkUploaded(UPLOAD_ID, 4);

        assertThat(service.getUploadedChunks(UPLOAD_ID, 5)).containsExactly(0, 2, 4);
    }

    @Test
    void getMissingChunks_complements_uploaded() {
        service.markChunkUploaded(UPLOAD_ID, 0);
        service.markChunkUploaded(UPLOAD_ID, 2);

        assertThat(service.getMissingChunks(UPLOAD_ID, 5)).containsExactly(1, 3, 4);
    }

    @Test
    void isComplete_true_only_when_every_chunk_marked() {
        service.markChunkUploaded(UPLOAD_ID, 0);
        service.markChunkUploaded(UPLOAD_ID, 1);
        assertThat(service.isComplete(UPLOAD_ID, 3)).isFalse();

        service.markChunkUploaded(UPLOAD_ID, 2);
        assertThat(service.isComplete(UPLOAD_ID, 3)).isTrue();
    }

    @Test
    void isComplete_false_for_zero_total() {
        assertThat(service.isComplete(UPLOAD_ID, 0)).isFalse();
    }

    @Test
    void cleanup_removes_bitmap() {
        service.markChunkUploaded(UPLOAD_ID, 0);
        service.markChunkUploaded(UPLOAD_ID, 1);

        service.cleanupUpload(UPLOAD_ID);

        verify(redis, atLeastOnce()).delete(eq("zhitu:upload:" + UPLOAD_ID));
        assertThat(service.isChunkUploaded(UPLOAD_ID, 0)).isFalse();
    }

    @Test
    void cleanup_skips_blank_uploadId() {
        service.cleanupUpload("");
        service.cleanupUpload(null);

        verify(redis, never()).delete(any(String.class));
    }

    @Test
    void invalid_uploadId_or_chunkIndex_throws() {
        assertThatThrownBy(() -> service.markChunkUploaded("", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.markChunkUploaded(UPLOAD_ID, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getUploadedChunks(UPLOAD_ID, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
