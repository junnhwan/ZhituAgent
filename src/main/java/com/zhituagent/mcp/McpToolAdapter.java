package com.zhituagent.mcp;

import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges an {@link McpToolSpec} into the in-process {@link ToolDefinition}
 * abstraction so MCP-discovered tools flow through {@code ToolRegistry},
 * {@code ToolCallExecutor} (parallel exec, schema validation, loop detection,
 * HITL approval gate), and the LLM function-calling spec exporter without
 * special-casing.
 *
 * <p>Tool name is prefixed with the originating MCP server name (e.g.
 * {@code mock-mcp.calculator}) to avoid colliding with locally-registered tool
 * names and to keep the source obvious in trace spans.
 */
public class McpToolAdapter implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    private final McpClient client;
    private final McpToolSpec spec;
    private final String qualifiedName;

    public McpToolAdapter(McpClient client, McpToolSpec spec) {
        this.client = client;
        this.spec = spec;
        this.qualifiedName = client.name() + "." + spec.name();
    }

    @Override
    public String name() {
        return qualifiedName;
    }

    @Override
    public String description() {
        String prefix = "[mcp:" + client.name() + "] ";
        String body = spec.description() == null ? spec.name() : spec.description();
        return prefix + body;
    }

    @Override
    public JsonObjectSchema parameterSchema() {
        return spec.inputSchema() == null ? JsonObjectSchema.builder().build() : spec.inputSchema();
    }

    @Override
    public ToolSpecification toolSpecification() {
        return ToolSpecification.builder()
                .name(qualifiedName)
                .description(description())
                .parameters(parameterSchema())
                .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            McpCallResult result = client.callTool(spec.name(), arguments);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "mcp");
            payload.put("mcpServer", client.name());
            payload.put("mcpTransport", client.transport());
            payload.put("mcpTool", spec.name());
            payload.put("metadata", result.metadata());
            if (result.isError()) {
                return new ToolResult(qualifiedName, false, "mcp tool error: " + result.content(), payload);
            }
            return new ToolResult(qualifiedName, true, result.content(), payload);
        } catch (RuntimeException exception) {
            log.warn("mcp call failed mcp.tool.failed server={} tool={} message={}",
                    client.name(), spec.name(), exception.getMessage());
            return new ToolResult(
                    qualifiedName,
                    false,
                    "mcp client failed: " + exception.getMessage(),
                    Map.of(
                            "source", "mcp",
                            "mcpServer", client.name(),
                            "mcpTransport", client.transport(),
                            "mcpTool", spec.name()
                    )
            );
        }
    }
}
