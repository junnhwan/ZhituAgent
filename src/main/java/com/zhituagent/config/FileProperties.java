package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File ingestion pipeline tuning. Controls multipart-driven upload chunk
 * threshold, parent-child chunking parameters for Tika parsing, and the
 * heap-usage guard that protects the process from OOM during parse.
 */
@ConfigurationProperties(prefix = "zhitu.file")
public class FileProperties {

    /** Multipart files larger than this should use the chunked-upload endpoint. */
    private long uploadChunkSize = 5L * 1024 * 1024;

    /** Coarse parent window — used as context prefix for child chunks. */
    private int parentChunkSize = 1024 * 1024;

    /** Fine child chunk size in chars; what the embeddings actually index. */
    private int childChunkSize = 512;

    /** Char overlap between consecutive child chunks to preserve boundary context. */
    private int chunkOverlap = 64;

    /** Heap-usage ratio above which Tika parse refuses (after one GC retry). */
    private double memoryThreshold = 0.8;

    public long getUploadChunkSize() {
        return uploadChunkSize;
    }

    public void setUploadChunkSize(long uploadChunkSize) {
        this.uploadChunkSize = uploadChunkSize;
    }

    public int getParentChunkSize() {
        return parentChunkSize;
    }

    public void setParentChunkSize(int parentChunkSize) {
        this.parentChunkSize = parentChunkSize;
    }

    public int getChildChunkSize() {
        return childChunkSize;
    }

    public void setChildChunkSize(int childChunkSize) {
        this.childChunkSize = childChunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public double getMemoryThreshold() {
        return memoryThreshold;
    }

    public void setMemoryThreshold(double memoryThreshold) {
        this.memoryThreshold = memoryThreshold;
    }
}
