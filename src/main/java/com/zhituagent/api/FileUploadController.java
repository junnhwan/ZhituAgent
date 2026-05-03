package com.zhituagent.api;

import com.zhituagent.file.ChunkedUploadService;
import com.zhituagent.file.FileIngestService;
import com.zhituagent.file.FileParseStatusService;
import com.zhituagent.file.FileUploadEvent;
import com.zhituagent.file.FileUploadEventProducer;
import com.zhituagent.file.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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
 * REST entry point for the file ingestion pipeline.
 *
 * <p><b>M3 mode toggle</b>: when {@code zhitu.infrastructure.kafka-enabled=true},
 * {@code /upload} and {@code /merge} hand off to {@link FileUploadEventProducer}
 * and return HTTP 202 immediately — the consumer drives Tika+embed+ES bulk
 * asynchronously. With Kafka disabled the M2 sync path stays in effect so
 * tests and dev environments without Kafka still work end-to-end.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/files/upload} — single-shot upload (async 202 if Kafka,
 *       else sync 200 with chunk count)</li>
 *   <li>{@code POST /api/files/chunk} — push one part of a multi-part upload
 *       to MinIO; idempotent on chunk re-send</li>
 *   <li>{@code POST /api/files/merge} — server-side compose once all chunks
 *       present (async 202 if Kafka, else sync 200)</li>
 *   <li>{@code GET  /api/files/status/{uploadId}} — async pipeline status
 *       (queued / parsing / indexed / failed) when Kafka is enabled, otherwise
 *       chunk-completion view</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/files")
@ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "minio-enabled", havingValue = "true")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    private final MinioStorageService minio;
    private final ChunkedUploadService chunked;
    private final FileIngestService ingest;
    private final FileUploadEventProducer eventProducer;
    private final FileParseStatusService statusService;

    @Autowired
    public FileUploadController(MinioStorageService minio,
                                ChunkedUploadService chunked,
                                FileIngestService ingest,
                                org.springframework.beans.factory.ObjectProvider<FileUploadEventProducer> eventProducer,
                                org.springframework.beans.factory.ObjectProvider<FileParseStatusService> statusService) {
        this.minio = minio;
        this.chunked = chunked;
        this.ingest = ingest;
        this.eventProducer = eventProducer.getIfAvailable();
        this.statusService = statusService.getIfAvailable();
    }

    /** Test-friendly constructor for sync-mode (no Kafka) wiring. */
    public FileUploadController(MinioStorageService minio,
                                ChunkedUploadService chunked,
                                FileIngestService ingest) {
        this(minio, chunked, ingest, (FileUploadEventProducer) null, (FileParseStatusService) null);
    }

    /** Direct constructor when test wants to supply the Kafka collaborators explicitly. */
    public FileUploadController(MinioStorageService minio,
                                ChunkedUploadService chunked,
                                FileIngestService ingest,
                                FileUploadEventProducer eventProducer,
                                FileParseStatusService statusService) {
        this.minio = minio;
        this.chunked = chunked;
        this.ingest = ingest;
        this.eventProducer = eventProducer;
        this.statusService = statusService;
    }

    private boolean asyncEnabled() {
        return eventProducer != null && statusService != null;
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

        if (asyncEnabled()) {
            statusService.mark(uploadId, FileParseStatusService.Status.QUEUED);
            eventProducer.publish(new FileUploadEvent(uploadId, sourceName, objectKey, file.getContentType()));
            log.info("File single-upload queued (async) uploadId={} sourceName={}", uploadId, sourceName);
            return ResponseEntity.accepted().body(new UploadResponse(uploadId, sourceName, objectKey, -1));
        }

        int chunks = ingest.ingestFromMinio(objectKey, sourceName, file.getContentType());
        log.info("File single-upload completed (sync) uploadId={} sourceName={} chunks={}",
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
        chunked.cleanupUpload(req.uploadId());

        if (asyncEnabled()) {
            statusService.mark(req.uploadId(), FileParseStatusService.Status.QUEUED);
            eventProducer.publish(new FileUploadEvent(req.uploadId(), req.sourceName(), mergedKey, req.contentType()));
            log.info("File merge queued (async) uploadId={} mergedKey={}", req.uploadId(), mergedKey);
            return ResponseEntity.accepted().body(
                    new UploadResponse(req.uploadId(), req.sourceName(), mergedKey, -1));
        }

        int chunks = ingest.ingestFromMinio(mergedKey, req.sourceName(), req.contentType());
        log.info("File merge completed (sync) uploadId={} mergedKey={} chunks={}",
                req.uploadId(), mergedKey, chunks);
        return ResponseEntity.ok(new UploadResponse(req.uploadId(), req.sourceName(), mergedKey, chunks));
    }

    @GetMapping("/status/{uploadId}")
    public ResponseEntity<UploadStatusResponse> getStatus(
            @PathVariable("uploadId") String uploadId,
            @RequestParam(value = "totalChunks", required = false, defaultValue = "0") int totalChunks) {
        if (uploadId == null || uploadId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String parseStatus = null;
        if (asyncEnabled()) {
            FileParseStatusService.Status s = statusService.get(uploadId);
            parseStatus = s == null ? null : s.name();
        }

        if (totalChunks > 0) {
            List<Integer> uploaded = chunked.getUploadedChunks(uploadId, totalChunks);
            List<Integer> missing = chunked.getMissingChunks(uploadId, totalChunks);
            boolean complete = missing.isEmpty();
            return ResponseEntity.ok(new UploadStatusResponse(uploadId, uploaded, missing, complete, parseStatus));
        }

        if (parseStatus == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(new UploadStatusResponse(uploadId, List.of(), List.of(), true, parseStatus));
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
    public record UploadStatusResponse(String uploadId, List<Integer> uploaded, List<Integer> missing,
                                        boolean complete, String parseStatus) {}
}
