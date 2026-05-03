package com.zhituagent.file;

/**
 * One unit of parsed document content ready to be embedded.
 *
 * <p>{@code content} is the chunk text that surfaces as evidence. {@code
 * parentContext} is the head of the parent window (~256 chars) the chunk
 * lives in, used by {@link com.zhituagent.rag.KnowledgeIngestService} to
 * synthesise an Anthropic-style contextual {@code embedText} for dense
 * retrieval.
 *
 * <p>Modeled as a record because chunks are immutable values once Tika
 * finishes parsing.
 */
public record ParsedChunk(String content, String parentContext) {

    public ParsedChunk(String content) {
        this(content, null);
    }

    public boolean hasParentContext() {
        return parentContext != null && !parentContext.isBlank();
    }
}
