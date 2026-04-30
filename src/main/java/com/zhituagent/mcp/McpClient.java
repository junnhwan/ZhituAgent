package com.zhituagent.mcp;

import java.util.List;
import java.util.Map;

/**
 * Minimal client surface for the Model Context Protocol. Two methods only —
 * everything the in-process tool layer needs to expose remote tools to the LLM
 * via {@link McpToolAdapter}.
 *
 * <p>Production implementations would speak JSON-RPC over stdio or SSE to a
 * real MCP server (think: Anthropic's reference servers, GitHub's MCP, etc.);
 * the {@link MockMcpClient} provides a deterministic in-memory implementation
 * for fixture-driven testing and local demos until the
 * {@code io.modelcontextprotocol} Java SDK is stable enough to depend on
 * directly.
 */
public interface McpClient {

    String name();

    List<McpToolSpec> listTools();

    McpCallResult callTool(String toolName, Map<String, Object> arguments);
}
