package com.zhituagent.api;

import com.zhituagent.file.ChunkedUploadService;
import com.zhituagent.file.FileIngestService;
import com.zhituagent.file.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * REST entry point for the file ingestion pipeline. Four endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/files/upload} — single-shot upload, parses + ingests
 *       inline. Use for files comfortably under {@code zhitu.file.upload-chunk-size}.</li>
 *   <li>{@code POST /api/files/chunk} — push one part of a multi-part upload to
 *       MinIO and mark its bit in the resume bitmap. Idempotent on chunk re-send.</li>
 *   <li>{@code POST /api/files/merge} — call once all chunks are present;
 *       server-side composeObject avoids re-uploading bytes.</li>
 *   <li>{@code GET  /api/files/status/{uploadId}} — what's in / what's missing
 *       so the client can selectively resume after a network drop.</li>
 * </ul>
 *
 * <p>Bean is conditional on the file pipeline being fully wired (MinIO + Redis
 * + ingest service all present). Missing any of those, the endpoint is absent
 * — clients get a clean 404 instead of a runtime NPE.
 */
@RestController
@RequestMapping("/api/files")
@ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "minio-enabled", havingValue = "true")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    private final MinioStorageService minio;
    private final ChunkedUploadService chunked;
    private final FileIngestService ingest;

    public FileUploadController(MinioStorageService minio,
                                ChunkedUploadService chunked,
                                FileIngestService ingest) {
        this.minio = minio;
        this.chunked = chunked;
        this.ingest = ingest;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadSingleFile(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String sourceName = sanitiseSourceName(file.getOriginalFilename());
        String uploadId = generateUploadId(sourceName);
        String objectKey = "uploads/" + uploadId + "/" + sourceName;

        minio.putObject(objectKey, file.getInputStream(), file.getSize(), file.getContentType());
        int chunks = ingest.ingestFromMinio(objectKey, sourceName, file.getContentType());

        log.info("File single-upload completed uploadId={} sourceName={} chunks={}",
                uploadId, sourceName, chunks);
        return ResponseEntity.ok(new UploadResponse(uploadId, sourceName, objectKey, chunks));
    }

    @PostMapping("/chunk")
    public ResponseEntity<ChunkUploadResponse> uploadChunk(
            @RequestParam("file") MultipartFile file,
            @RequestParam("uploadId") String uploadId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks)
            throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (uploadId == null || uploadId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (chunkIndex < 0 || totalChunks <= 0 || chunkIndex >= totalChunks) {
            return ResponseEntity.badRequest().build();
        }

        String objectKey = chunkObjectKey(uploadId, chunkIndex);
        if (chunked.isChunkUploaded(uploadId, chunkIndex)) {
            log.info("Chunk re-upload short-circuited (idempotent) uploadId={} chunkIndex={}",
                    uploadId, chunkIndex);
        } else {
            minio.putObject(objectKey, file.getInputStream(), file.getSize(), file.getContentType());
            chunked.markChunkUploaded(uploadId, chunkIndex);
        }

        List<Integer> missing = chunked.getMissingChunks(uploadId, totalChunks);
        return ResponseEntity.ok(new ChunkUploadResponse(uploadId, chunkIndex, missing));
    }

    @PostMapping("/merge")
    public ResponseEntity<UploadResponse> mergeChunks(@RequestBody MergeRequest req) {
        if (req == null || req.uploadId() == null || req.uploadId().isBlank()
                || req.sourceName() == null || req.sourceName().isBlank()
                || req.totalChunks() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (!chunked.isComplete(req.uploadId(), req.totalChunks())) {
            List<Integer> missing = chunked.getMissingChunks(req.uploadId(), req.totalChunks());
            log.warn("Merge requested before all chunks present uploadId={} missing={}",
                    req.uploadId(), missing);
            return ResponseEntity.unprocessableEntity().build();
        }

        List<String> sourceKeys = chunkObjectKeys(req.uploadId(), req.totalChunks());
        String mergedKey = "uploads/" + req.uploadId() + "/" + sanitiseSourceName(req.sourceName());
        minio.composeObject(mergedKey, sourceKeys);
        int chunks = ingest.ingestFromMinio(mergedKey, req.sourceName(), req.contentType());
        chunked.cleanupUpload(req.uploadId());

        log.info("File merge completed uploadId={} mergedKey={} chunks={}",
                req.uploadId(), mergedKey, chunks);
        return ResponseEntity.ok(new UploadResponse(req.uploadId(), req.sourceName(), mergedKey, chunks));
    }

    @GetMapping("/status/{uploadId}")
    public ResponseEntity<UploadStatusResponse> getStatus(
            @PathVariable("uploadId") String uploadId,
            @RequestParam("totalChunks") int totalChunks) {
        if (uploadId == null || uploadId.isBlank() || totalChunks <= 0) {
            return ResponseEntity.badRequest().build();
        }
        List<Integer> uploaded = chunked.getUploadedChunks(uploadId, totalChunks);
        List<Integer> missing = chunked.getMissingChunks(uploadId, totalChunks);
        boolean complete = missing.isEmpty();
        return ResponseEntity.ok(new UploadStatusResponse(uploadId, uploaded, missing, complete));
    }

    private static String chunkObjectKey(String uploadId, int chunkIndex) {
        return "chunks/" + uploadId + "/" + chunkIndex;
    }

    private static List<String> chunkObjectKeys(String uploadId, int totalChunks) {
        return java.util.stream.IntStream.range(0, totalChunks)
                .mapToObj(i -> chunkObjectKey(uploadId, i))
                .toList();
    }

    private static String sanitiseSourceName(String original) {
        if (original == null || original.isBlank()) {
            return "unnamed";
        }
        return original.replaceAll("[\\\\/]", "_").trim();
    }

    private static String generateUploadId(String sourceName) {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public record UploadResponse(String uploadId, String sourceName, String objectKey, int chunksIngested) {}
    public record ChunkUploadResponse(String uploadId, int chunkIndex, List<Integer> missing) {}
    public record MergeRequest(String uploadId, int totalChunks, String sourceName, String contentType) {}
    public record UploadStatusResponse(String uploadId, List<Integer> uploaded, List<Integer> missing, boolean complete) {}
}
