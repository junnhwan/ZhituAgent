package com.zhituagent.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

/**
 * Tracks lifecycle of an async upload through the parse pipeline so the client
 * can poll {@code GET /api/files/status/{uploadId}} to see whether its file is
 * still queued, parsing, or fully indexed.
 *
 * <p>State stored in Redis at {@code zhitu:upload:status:{uploadId}} with a 24h
 * TTL — long enough for retry storms, short enough not to leak memory. The
 * status enum follows a strict forward progression: {@code QUEUED → PARSING →
 * INDEXED} on success, or {@code FAILED} when the consumer DLT-routes after all
 * retries are exhausted.
 */
@Service
@ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "kafka-enabled", havingValue = "true")
public class FileParseStatusService {

    private static final Logger log = LoggerFactory.getLogger(FileParseStatusService.class);
    private static final String KEY_PREFIX = "zhitu:upload:status:";
    private static final Duration TTL = Duration.ofHours(24);

    public enum Status { QUEUED, PARSING, INDEXED, FAILED }

    private final StringRedisTemplate redis;

    public FileParseStatusService(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis);
    }

    public void mark(String uploadId, Status status) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        String key = KEY_PREFIX + uploadId;
        redis.opsForValue().set(key, status.name(), TTL);
        log.debug("Upload status uploadId={} status={}", uploadId, status);
    }

    public Status get(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            return null;
        }
        String raw = redis.opsForValue().get(KEY_PREFIX + uploadId);
        if (raw == null) {
            return null;
        }
        try {
            return Status.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
