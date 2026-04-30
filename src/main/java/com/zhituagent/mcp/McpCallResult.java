package com.zhituagent.mcp;

import java.util.Map;

/**
 * Result of an MCP {@code tools/call} invocation. {@code content} holds the
 * stringified primary payload (MCP servers return content blocks; we collapse
 * to text for the LLM observation), and {@code metadata} carries any structured
 * fields the server returns alongside.
 */
public record McpCallResult(
        boolean isError,
        String content,
        Map<String, Object> metadata
) {

    public static McpCallResult ok(String content, Map<String, Object> metadata) {
        return new McpCallResult(false, content == null ? "" : content, metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    public static McpCallResult error(String message) {
        return new McpCallResult(true, message == null ? "" : message, Map.of());
    }
}
