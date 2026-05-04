package com.zhituagent.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.mcp.McpCallResult;
import com.zhituagent.mcp.McpClient;
import com.zhituagent.mcp.McpProperties;
import com.zhituagent.mcp.McpToolSpec;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real MCP {@link com.zhituagent.mcp.McpClient} implementation backed by the
 * official Java SDK ({@code io.modelcontextprotocol.sdk:mcp}). Wraps the SDK's
 * {@link McpSyncClient} so the rest of the codebase keeps depending on the
 * project-local {@link com.zhituagent.mcp.McpClient} abstraction — no
 * LangChain4j-specific or SDK-specific types leak into the tool registry /
 * adapter layer.
 *
 * <p>Supports two transports today, picked by
 * {@link McpProperties.ServerConfig#getTransport()}:
 * <ul>
 *   <li>{@code stdio} — subprocess-based (Tavily, Filesystem, Prometheus, etc.)</li>
 *   <li>{@code streamable-http} — HTTP+SSE bidirectional per MCP spec 2025-06-18
 *       (Baidu Search MCP, hosted servers)</li>
 * </ul>
 *
 * <p>Initialization runs synchronously with a 15s timeout. If init fails the
 * constructor throws so {@code WebConfig#mcpClients} can filter the broken
 * server out without taking down the others.
 */
public class OfficialSdkMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(OfficialSdkMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * SDK 2.0.0-M2's transports require an explicit {@link McpJsonMapper}
     * (Jackson 3-backed by default). Cached as a static singleton — supplier
     * lookup is non-trivial and the resulting mapper is stateless / thread-safe.
     */
    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapperSupplier().get();

    private final McpProperties.ServerConfig config;
    private final McpSyncClient sdkClient;
    private volatile boolean healthy;

    public OfficialSdkMcpClient(McpProperties.ServerConfig config) {
        this.config = config;
        this.sdkClient = buildSdkClient(config);
        try {
            this.sdkClient.initialize();
            this.healthy = true;
            log.info("MCP server connected mcp.connect server={} transport={}",
                    config.getName(), config.getTransport());
        } catch (RuntimeException exception) {
            this.healthy = false;
            log.warn("MCP server init failed mcp.connect.failed server={} transport={} reason={}",
                    config.getName(), config.getTransport(), exception.getMessage());
            throw new RuntimeException(
                    "mcp init failed: " + config.getName() + " (" + exception.getMessage() + ")",
                    exception);
        }
    }

    private static McpSyncClient buildSdkClient(McpProperties.ServerConfig cfg) {
        McpClientTransport transport = createTransport(cfg);
        return io.modelcontextprotocol.client.McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
    }

    private static McpClientTransport createTransport(McpProperties.ServerConfig cfg) {
        String transportType = cfg.getTransport() == null ? "stdio" : cfg.getTransport().toLowerCase();
        return switch (transportType) {
            case "stdio" -> {
                List<String> args = cfg.getArgs() == null ? List.of() : cfg.getArgs();
                ServerParameters.Builder paramsBuilder = ServerParameters.builder(cfg.getCommand())
                        .args(args.toArray(String[]::new));
                if (cfg.getEnv() != null && !cfg.getEnv().isEmpty()) {
                    paramsBuilder.env(cfg.getEnv());
                }
                yield new StdioClientTransport(paramsBuilder.build(), JSON_MAPPER);
            }
            case "streamable-http", "http" -> {
                if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                    throw new IllegalArgumentException(
                            "streamable-http transport requires url for server: " + cfg.getName());
                }
                yield HttpClientStreamableHttpTransport
                        .builder(cfg.getUrl())
                        .jsonMapper(JSON_MAPPER)
                        .build();
            }
            default -> throw new IllegalArgumentException(
                    "unsupported MCP transport: " + cfg.getTransport()
                            + " (server=" + cfg.getName() + "); supported: stdio, streamable-http");
        };
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String transport() {
        return config.getTransport() == null ? "stdio" : config.getTransport();
    }

    @Override
    public List<McpToolSpec> listTools() {
        ensureHealthy();
        try {
            ListToolsResult result = sdkClient.listTools();
            List<Tool> tools = result.tools();
            List<McpToolSpec> specs = new ArrayList<>(tools.size());
            for (Tool tool : tools) {
                JsonObjectSchema schema = convertSchema(tool.inputSchema());
                specs.add(new McpToolSpec(tool.name(), tool.description(), schema));
            }
            return specs;
        } catch (RuntimeException exception) {
            throw new RuntimeException(
                    "mcp listTools failed: " + name() + " (" + exception.getMessage() + ")",
                    exception);
        }
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments) {
        ensureHealthy();
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            CallToolResult result = sdkClient.callTool(new CallToolRequest(toolName, args));
            String content = collapseContent(result.content());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("server", name());
            metadata.put("transport", transport());
            boolean isError = Boolean.TRUE.equals(result.isError());
            return new McpCallResult(isError, content, metadata);
        } catch (RuntimeException exception) {
            throw new RuntimeException(
                    "mcp callTool failed: " + name() + "/" + toolName
                            + " (" + exception.getMessage() + ")",
                    exception);
        }
    }

    @PreDestroy
    public void close() {
        try {
            sdkClient.closeGracefully();
            log.info("MCP server closed mcp.close server={}", name());
        } catch (RuntimeException exception) {
            log.warn("MCP server close failed mcp.close.failed server={} reason={}",
                    name(), exception.getMessage());
        }
    }

    private void ensureHealthy() {
        if (!healthy) {
            throw new IllegalStateException("mcp client not healthy: " + name());
        }
    }

    private static String collapseContent(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Content c : contents) {
            if (c instanceof TextContent text) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(text.text());
            }
            // Skip non-text content (image/resource) — LLM observation is text-only.
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static JsonObjectSchema convertSchema(Object sdkSchema) {
        if (sdkSchema == null) {
            return JsonObjectSchema.builder().build();
        }
        Map<String, Object> schemaMap;
        try {
            schemaMap = MAPPER.convertValue(sdkSchema, Map.class);
        } catch (RuntimeException exception) {
            log.debug("MCP tool schema not convertible, falling back to free-form mcp.schema.fallback reason={}",
                    exception.getMessage());
            return JsonObjectSchema.builder().build();
        }
        if (schemaMap == null) {
            return JsonObjectSchema.builder().build();
        }
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        Object propsObj = schemaMap.get("properties");
        if (propsObj instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                String propName = String.valueOf(entry.getKey());
                String description = "";
                if (entry.getValue() instanceof Map<?, ?> propSpec) {
                    Object desc = propSpec.get("description");
                    if (desc != null) {
                        description = String.valueOf(desc);
                    }
                }
                // Simplify all properties to string; LLM adapts via description.
                // Trade-off: lose strict typing but avoid full JSON Schema 2020-12
                // → langchain4j JsonObjectSchema mapping (the latter doesn't
                // model nested objects/arrays/oneOf/etc. cleanly enough for
                // arbitrary MCP tool schemas).
                builder.addStringProperty(propName, description);
            }
        }
        Object reqObj = schemaMap.get("required");
        if (reqObj instanceof List<?> req && !req.isEmpty()) {
            String[] required = req.stream().map(String::valueOf).toArray(String[]::new);
            builder.required(required);
        }
        return builder.build();
    }
}
