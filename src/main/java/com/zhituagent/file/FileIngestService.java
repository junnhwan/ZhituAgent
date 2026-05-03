package com.zhituagent.file;

import com.zhituagent.rag.KnowledgeChunk;
import com.zhituagent.rag.KnowledgeIngestService;
import com.zhituagent.rag.KnowledgeStoreIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * End-to-end glue for the file ingestion pipeline:
 * <ol>
 *   <li>pull the merged upload from MinIO,</li>
 *   <li>let Tika parse it into parent-child {@link ParsedChunk}s,</li>
 *   <li>convert to {@link KnowledgeChunk}s (chunkId via SHA-256 hash so re-ingest
 *       is idempotent UPSERT) and write to the active {@code KnowledgeStore}
 *       through {@link KnowledgeIngestService}.</li>
 * </ol>
 *
 * <p>Embed text follows Anthropic Contextual Retrieval shape
 * ({@code parentContext + "\n\n" + content}) but is constructed deterministically
 * from the parent window — no LLM call in the ingest hot path. The
 * LLM-driven {@code ContextualChunkAnnotator} stays available for the small
 * Q:A ingest path where it's cheap.
 *
 * <p>Bean is conditional on {@link MinioClient} being wired so contexts without
 * MinIO can still boot.
 */
@Service
@ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "minio-enabled", havingValue = "true")
public class FileIngestService {

    private static final Logger log = LoggerFactory.getLogger(FileIngestService.class);

    private final MinioStorageService minio;
    private final TikaParseService tika;
    private final KnowledgeIngestService knowledge;

    public FileIngestService(MinioStorageService minio,
                             TikaParseService tika,
                             KnowledgeIngestService knowledge) {
        this.minio = Objects.requireNonNull(minio);
        this.tika = Objects.requireNonNull(tika);
        this.knowledge = Objects.requireNonNull(knowledge);
    }

    /**
     * Read object {@code objectKey} from MinIO, parse via Tika, ingest into the
     * knowledge store under {@code sourceName}. Returns the number of chunks
     * persisted (0 when the file parsed to whitespace).
     */
    public int ingestFromMinio(String objectKey, String sourceName, String contentType) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName must not be blank");
        }

        List<ParsedChunk> parsed;
        try (InputStream stream = minio.getObject(objectKey)) {
            parsed = tika.parse(stream, sourceName, contentType);
        } catch (IOException e) {
            throw new IllegalStateException("FileIngest failed reading " + objectKey, e);
        }

        if (parsed.isEmpty()) {
            log.info("Tika returned no chunks, skipping ingest objectKey={} sourceName={}",
                    objectKey, sourceName);
            return 0;
        }

        List<KnowledgeChunk> knowledgeChunks = toKnowledgeChunks(sourceName, parsed);
        knowledge.ingest(knowledgeChunks);
        log.info("File ingest completed objectKey={} sourceName={} chunks={}",
                objectKey, sourceName, knowledgeChunks.size());
        return knowledgeChunks.size();
    }

    private static List<KnowledgeChunk> toKnowledgeChunks(String sourceName, List<ParsedChunk> parsed) {
        return parsed.stream()
                .filter(pc -> pc.content() != null && !pc.content().isBlank())
                .map(pc -> new KnowledgeChunk(
                        sourceName,
                        KnowledgeStoreIds.computeChunkId(sourceName, pc.content()),
                        pc.content(),
                        buildEmbedText(pc)
                ))
                .toList();
    }

    /**
     * Anthropic Contextual Retrieval shape: prepend the parent window head as
     * disambiguating context, leave dense embedding to do the rest. Returns null
     * when no parent context exists (chunk equals its own parent).
     */
    private static String buildEmbedText(ParsedChunk pc) {
        if (!pc.hasParentContext()) {
            return null;
        }
        return pc.parentContext() + "\n\n" + pc.content();
    }
}
