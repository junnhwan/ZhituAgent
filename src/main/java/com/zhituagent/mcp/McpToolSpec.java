package com.zhituagent.mcp;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Tool descriptor returned by an MCP server's {@code tools/list} call.
 * Mirrors the JSON-RPC {@code Tool} shape from the Model Context Protocol spec
 * (modelcontextprotocol.io) so {@link McpToolAdapter} can wrap it as a
 * {@link com.zhituagent.tool.ToolDefinition} without further translation.
 */
public record McpToolSpec(
        String name,
        String description,
        JsonObjectSchema inputSchema
) {
}
