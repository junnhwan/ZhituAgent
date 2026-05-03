package com.zhituagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.file.ChunkedUploadService;
import com.zhituagent.file.FileIngestService;
import com.zhituagent.file.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileUploadControllerTest {

    private MinioStorageService minio;
    private ChunkedUploadService chunked;
    private FileIngestService ingest;
    private MockMvc mvc;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        minio = mock(MinioStorageService.class);
        chunked = mock(ChunkedUploadService.class);
        ingest = mock(FileIngestService.class);
        FileUploadController controller = new FileUploadController(minio, chunked, ingest);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        json = new ObjectMapper();
    }

    // ---------- POST /api/files/upload (single-shot) ----------

    @Test
    void singleUpload_putsObject_then_ingests_and_returns_chunkCount() throws Exception {
        MockMultipartFile mp = new MockMultipartFile(
                "file", "handbook.txt", "text/plain", "hello world".getBytes());
        when(ingest.ingestFromMinio(anyString(), eq("handbook.txt"), eq("text/plain")))
                .thenReturn(7);

        mvc.perform(multipart("/api/files/upload").file(mp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceName").value("handbook.txt"))
                .andExpect(jsonPath("$.chunksIngested").value(7));

        verify(minio, times(1)).putObject(anyString(), any(InputStream.class), anyLong(), eq("text/plain"));
        verify(ingest, times(1)).ingestFromMinio(anyString(), eq("handbook.txt"), eq("text/plain"));
    }

    @Test
    void singleUpload_rejects_empty_file() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "x.txt", "text/plain", new byte[0]);

        mvc.perform(multipart("/api/files/upload").file(empty))
                .andExpect(status().isBadRequest());
        verify(minio, never()).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void singleUpload_sanitises_path_separators_in_filename() throws Exception {
        MockMultipartFile mp = new MockMultipartFile(
                "file", "../etc/secret.txt", "text/plain", "x".getBytes());
        when(ingest.ingestFromMinio(anyString(), anyString(), anyString())).thenReturn(1);

        mvc.perform(multipart("/api/files/upload").file(mp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceName").value(".._etc_secret.txt"));
    }

    // ---------- POST /api/files/chunk ----------

    @Test
    void chunkUpload_puts_to_minio_and_marks_bitmap() throws Exception {
        MockMultipartFile mp = new MockMultipartFile(
                "file", "p2", "application/octet-stream", new byte[]{1, 2, 3});
        when(chunked.isChunkUploaded("u-1", 2)).thenReturn(false);
        when(chunked.getMissingChunks("u-1", 5)).thenReturn(List.of(0, 1, 3, 4));

        mvc.perform(multipart("/api/files/chunk").file(mp)
                        .param("uploadId", "u-1")
                        .param("chunkIndex", "2")
                        .param("totalChunks", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value("u-1"))
                .andExpect(jsonPath("$.chunkIndex").value(2))
                .andExpect(jsonPath("$.missing.length()").value(4));

        verify(minio).putObject(eq("chunks/u-1/2"), any(InputStream.class), anyLong(), anyString());
        verify(chunked).markChunkUploaded("u-1", 2);
    }

    @Test
    void chunkUpload_already_uploaded_skips_minio_put_idempotent() throws Exception {
        MockMultipartFile mp = new MockMultipartFile(
                "file", "p2", "application/octet-stream", new byte[]{1, 2, 3});
        when(chunked.isChunkUploaded("u-1", 2)).thenReturn(true);
        when(chunked.getMissingChunks("u-1", 5)).thenReturn(List.of(0, 1, 3, 4));

        mvc.perform(multipart("/api/files/chunk").file(mp)
                        .param("uploadId", "u-1")
                        .param("chunkIndex", "2")
                        .param("totalChunks", "5"))
                .andExpect(status().isOk());

        verify(minio, never()).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
        verify(chunked, never()).markChunkUploaded(anyString(), anyInt());
    }

    @Test
    void chunkUpload_rejects_chunkIndex_out_of_range() throws Exception {
        MockMultipartFile mp = new MockMultipartFile(
                "file", "p", "application/octet-stream", new byte[]{1});

        mvc.perform(multipart("/api/files/chunk").file(mp)
                        .param("uploadId", "u-1")
                        .param("chunkIndex", "5")
                        .param("totalChunks", "5"))
                .andExpect(status().isBadRequest());
    }

    // ---------- POST /api/files/merge ----------

    @Test
    void merge_composes_then_ingests_and_cleans_up_bitmap() throws Exception {
        when(chunked.isComplete("u-9", 3)).thenReturn(true);
        when(ingest.ingestFromMinio(anyString(), eq("doc.pdf"), eq("application/pdf"))).thenReturn(12);

        String body = json.writeValueAsString(new FileUploadController.MergeRequest(
                "u-9", 3, "doc.pdf", "application/pdf"));

        mvc.perform(post("/api/files/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value("uploads/u-9/doc.pdf"))
                .andExpect(jsonPath("$.chunksIngested").value(12));

        verify(minio).composeObject(eq("uploads/u-9/doc.pdf"),
                eq(List.of("chunks/u-9/0", "chunks/u-9/1", "chunks/u-9/2")));
        verify(chunked).cleanupUpload("u-9");
    }

    @Test
    void merge_returns_422_when_chunks_missing() throws Exception {
        when(chunked.isComplete("u-9", 3)).thenReturn(false);
        when(chunked.getMissingChunks("u-9", 3)).thenReturn(List.of(2));

        String body = json.writeValueAsString(new FileUploadController.MergeRequest(
                "u-9", 3, "doc.pdf", "application/pdf"));

        mvc.perform(post("/api/files/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());

        verify(minio, never()).composeObject(anyString(), any());
        verify(chunked, never()).cleanupUpload(anyString());
    }

    // ---------- GET /api/files/status/{uploadId} ----------

    @Test
    void status_returns_uploaded_missing_and_complete_flag() throws Exception {
        when(chunked.getUploadedChunks("u-2", 5)).thenReturn(List.of(0, 1, 3));
        when(chunked.getMissingChunks("u-2", 5)).thenReturn(List.of(2, 4));

        mvc.perform(get("/api/files/status/u-2").param("totalChunks", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploaded.length()").value(3))
                .andExpect(jsonPath("$.missing.length()").value(2))
                .andExpect(jsonPath("$.complete").value(false));
    }
}
