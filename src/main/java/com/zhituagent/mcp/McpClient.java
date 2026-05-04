package com.zhituagent.mcp;

import java.util.List;
import java.util.Map;

/**
 * Minimal client surface for the Model Context Protocol. Three methods plus an
 * optional transport hint — everything the in-process tool layer needs to expose
 * remote tools to the LLM via {@link McpToolAdapter}.
 *
 * <p>Production implementations speak JSON-RPC over stdio or Streamable HTTP to
 * a real MCP server (see {@code mcp.client.OfficialSdkMcpClient} for the
 * {@code io.modelcontextprotocol.sdk:mcp}-backed implementation); the
 * {@link MockMcpClient} provides a deterministic in-memory implementation for
 * fixture-driven testing and local demos.
 */
public interface McpClient {

    String name();

    /**
     * Transport label exposed in tool result metadata / SSE events / span
     * attributes so the trace consumer can tell stdio servers apart from
     * Streamable HTTP ones in the UI. Default {@code "unknown"} keeps the
     * {@link MockMcpClient} working without modification.
     */
    default String transport() {
        return "unknown";
    }

    List<McpToolSpec> listTools();

    McpCallResult callTool(String toolName, Map<String, Object> arguments);
}
