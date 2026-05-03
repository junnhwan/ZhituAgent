package com.zhituagent.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tracks chunk-level completeness for resumable uploads using a Redis bitmap.
 *
 * <p>Each upload session owns a bitmap at {@code zhitu:upload:{uploadId}};
 * bit N is set when chunk N has been persisted in MinIO. Re-uploading the
 * same chunk is naturally idempotent — SETBIT on an already-set bit is a
 * no-op. The 24h TTL bounds session lifetime so abandoned uploads don't
 * leak Redis memory; every mark refreshes the TTL.
 *
 * <p>Bean is conditional on {@link StringRedisTemplate} so unit-test contexts
 * without Redis stay green.
 */
@Service
@ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "minio-enabled", havingValue = "true")
public class ChunkedUploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkedUploadService.class);
    private static final String KEY_PREFIX = "zhitu:upload:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public ChunkedUploadService(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis);
    }

    /**
     * Mark chunk {@code chunkIndex} as uploaded; idempotent — re-marking the
     * same chunk leaves the bitmap unchanged. TTL refreshed on every call so
     * an active upload session never expires mid-stream.
     */
    public void markChunkUploaded(String uploadId, int chunkIndex) {
        validate(uploadId, chunkIndex);
        String key = key(uploadId);
        redis.opsForValue().setBit(key, chunkIndex, true);
        redis.expire(key, SESSION_TTL);
        log.debug("Chunk marked uploaded uploadId={} chunkIndex={}", uploadId, chunkIndex);
    }

    public boolean isChunkUploaded(String uploadId, int chunkIndex) {
        validate(uploadId, chunkIndex);
        return Boolean.TRUE.equals(redis.opsForValue().getBit(key(uploadId), chunkIndex));
    }

    /**
     * Return all chunk indices already uploaded, in ascending order. Useful
     * when client resumes mid-upload and wants to know what's still missing.
     */
    public List<Integer> getUploadedChunks(String uploadId, int totalChunks) {
        if (totalChunks < 0) {
            throw new IllegalArgumentException("totalChunks must be >= 0");
        }
        List<Integer> uploaded = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (isChunkUploaded(uploadId, i)) {
                uploaded.add(i);
            }
        }
        return uploaded;
    }

    public List<Integer> getMissingChunks(String uploadId, int totalChunks) {
        if (totalChunks < 0) {
            throw new IllegalArgumentException("totalChunks must be >= 0");
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (!isChunkUploaded(uploadId, i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    public boolean isComplete(String uploadId, int totalChunks) {
        if (totalChunks <= 0) {
            return false;
        }
        for (int i = 0; i < totalChunks; i++) {
            if (!isChunkUploaded(uploadId, i)) {
                return false;
            }
        }
        return true;
    }

    /** Drop the bitmap once compose succeeded — keeps Redis tidy. */
    public void cleanupUpload(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            return;
        }
        Boolean deleted = redis.delete(key(uploadId));
        log.info("Upload session cleanup uploadId={} keyDeleted={}", uploadId, deleted);
    }

    private static String key(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId must not be blank");
        }
        return KEY_PREFIX + uploadId;
    }

    private static void validate(String uploadId, int chunkIndex) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId must not be blank");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0, got " + chunkIndex);
        }
    }
}
