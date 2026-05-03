package com.zhituagent.file;

import com.zhituagent.rag.KnowledgeChunk;
import com.zhituagent.rag.KnowledgeIngestService;
import com.zhituagent.rag.KnowledgeStoreIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileIngestServiceTest {

    private MinioStorageService minio;
    private TikaParseService tika;
    private KnowledgeIngestService knowledge;
    private FileIngestService service;

    @BeforeEach
    void setUp() {
        minio = mock(MinioStorageService.class);
        tika = mock(TikaParseService.class);
        knowledge = mock(KnowledgeIngestService.class);
        service = new FileIngestService(minio, tika, knowledge);
    }

    @Test
    void ingestFromMinio_pulls_parses_and_stores_chunks() {
        String objectKey = "uploads/2026/05/abc.pdf";
        String sourceName = "user-handbook.pdf";
        InputStream stream = utf8("file body");
        when(minio.getObject(objectKey)).thenReturn(stream);
        when(tika.parse(eq(stream), eq(sourceName), eq("application/pdf")))
                .thenReturn(List.of(
                        new ParsedChunk("Section A introduces the policy.", "Document overview: company handbook 2026."),
                        new ParsedChunk("Section B details the workflow.", "Document overview: company handbook 2026.")
                ));

        int count = service.ingestFromMinio(objectKey, sourceName, "application/pdf");

        assertThat(count).isEqualTo(2);
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledge).ingest(captor.capture());
        List<KnowledgeChunk> stored = captor.getValue();
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).source()).isEqualTo(sourceName);
        assertThat(stored.get(0).content()).contains("Section A");
        // Embed text follows "<context>\n\n<content>" Contextual Retrieval shape.
        assertThat(stored.get(0).embedText()).contains("Document overview").contains("Section A");
    }

    @Test
    void ingestFromMinio_uses_sha256_chunkId_so_repeat_ingest_is_idempotent() {
        String objectKey = "uploads/x.txt";
        String sourceName = "x.txt";
        when(minio.getObject(objectKey)).thenReturn(utf8("payload"));
        when(tika.parse(any(), any(), any()))
                .thenReturn(List.of(new ParsedChunk("identical content", "ctx")));

        service.ingestFromMinio(objectKey, sourceName, "text/plain");

        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledge).ingest(captor.capture());
        String expectedChunkId = KnowledgeStoreIds.computeChunkId(sourceName, "identical content");
        assertThat(captor.getValue().get(0).chunkId()).isEqualTo(expectedChunkId);
    }

    @Test
    void empty_parse_result_skips_ingest_call() {
        when(minio.getObject(any())).thenReturn(utf8(""));
        when(tika.parse(any(), any(), any())).thenReturn(List.of());

        int count = service.ingestFromMinio("uploads/empty.txt", "empty.txt", "text/plain");

        assertThat(count).isZero();
        verify(knowledge, never()).ingest(any(List.class));
    }

    @Test
    void blank_content_chunks_are_filtered_out() {
        when(minio.getObject(any())).thenReturn(utf8("body"));
        when(tika.parse(any(), any(), any()))
                .thenReturn(List.of(
                        new ParsedChunk("real content", "ctx"),
                        new ParsedChunk("   ", "ctx"),
                        new ParsedChunk("", "ctx")
                ));

        int count = service.ingestFromMinio("k.txt", "k.txt", "text/plain");

        assertThat(count).isEqualTo(1);
    }

    @Test
    void chunk_without_parent_context_omits_embedText() {
        when(minio.getObject(any())).thenReturn(utf8("solo"));
        when(tika.parse(any(), any(), any()))
                .thenReturn(List.of(new ParsedChunk("standalone", null)));

        service.ingestFromMinio("solo.txt", "solo.txt", "text/plain");

        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledge).ingest(captor.capture());
        assertThat(captor.getValue().get(0).embedText()).isNull();
    }

    @Test
    void minio_io_failure_wraps_as_illegal_state() {
        when(minio.getObject(any())).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("S3 read error");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("S3 close error");
            }
        });
        when(tika.parse(any(), any(), any()))
                .thenReturn(List.of(new ParsedChunk("any", "ctx")));

        // The close() during try-with-resources will surface — must wrap.
        assertThatThrownBy(() -> service.ingestFromMinio("k.txt", "k.txt", "text/plain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FileIngest failed reading");
    }

    @Test
    void invalid_inputs_throw_immediately() {
        assertThatThrownBy(() -> service.ingestFromMinio("", "src", "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingestFromMinio("k.txt", "", "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InputStream utf8(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
