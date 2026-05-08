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
 * <p>Tool name is prefixed with the originating MCP server name using a
 * double-underscore separator (e.g. {@code tavily__tavily_search}) — chosen
 * because OpenAI / GLM / most function-calling LLMs require tool names to
 * match {@code ^[a-zA-Z0-9_-]+$} (no dots allowed). Double underscore is the
 * de-facto MCP namespacing convention used by Anthropic / Spring AI as well,
 * and is unambiguous because real MCP tool names use single underscores.
 */
public class McpToolAdapter implements ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    // MCP → ToolDefinition 适配器：将 MCP 协议发现的外部工具桥接为内部 ToolDefinition，
    // 使其流经 ToolRegistry → ToolCallExecutor（并行执行、Schema 校验、循环检测、HITL 审批）
    // 和 LLM function-calling spec 导出，无需对 MCP 工具做特殊处理。
    // 工具名用双下划线连接 serverName__toolName，兼容 OpenAI/GLM/Anthropic 的命名正则。
    static final String SERVER_TOOL_SEPARATOR = "__";

    private final McpClient client;
    private final McpToolSpec spec;
    private final String qualifiedName;

    public McpToolAdapter(McpClient client, McpToolSpec spec) {
        this.client = client;
        this.spec = spec;
        this.qualifiedName = client.name() + SERVER_TOOL_SEPARATOR + spec.name();
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
