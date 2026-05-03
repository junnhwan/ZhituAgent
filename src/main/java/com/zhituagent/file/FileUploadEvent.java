package com.zhituagent.file;

/**
 * Async ingest envelope published after a MinIO put completes. Carries enough
 * pointer data for a consumer to drive Tika parse + embedding + ES bulkIndex
 * without re-uploading bytes.
 *
 * <p>Kept as a record (no logic) so JSON {@code (de)serialization} is trivial
 * across producer/consumer boundaries.
 */
public record FileUploadEvent(
        String uploadId,
        String sourceName,
        String objectKey,
        String contentType
) {
    public FileUploadEvent {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId must not be blank");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName must not be blank");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
    }
}
