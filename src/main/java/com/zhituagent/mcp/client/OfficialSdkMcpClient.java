package com.zhituagent.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.mcp.McpCallResult;
import com.zhituagent.mcp.McpClient;
import com.zhituagent.mcp.McpProperties;
import com.zhituagent.mcp.McpToolSpec;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
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
                ServerParameters.Builder paramsBuilder = ServerParameters.builder(resolveCommand(cfg.getCommand()))
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
                HttpClientStreamableHttpTransport.Builder httpBuilder = HttpClientStreamableHttpTransport
                        .builder(cfg.getUrl())
                        .jsonMapper(JSON_MAPPER);
                Map<String, String> headers = cfg.getHeaders();
                if (headers != null && !headers.isEmpty()) {
                    // Inject configured headers (e.g. Authorization: Bearer ${TOKEN})
                    // on every JSON-RPC HTTP request. The customizer is called once
                    // per request — cheap, thread-safe, no state to manage.
                    httpBuilder.httpRequestCustomizer(
                            (requestBuilder, method, uri, body, ctx) ->
                                    headers.forEach(requestBuilder::header));
                }
                yield httpBuilder.build();
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

    /**
     * Resolve a stdio command to its OS-specific executable name. On Windows,
     * npm-installed shims ({@code npx}, {@code npm}, {@code yarn}, {@code pnpm})
     * are {@code .cmd} batch scripts rather than {@code .exe} files — Java's
     * {@link ProcessBuilder} cannot start them without the explicit suffix and
     * fails with {@code Failed to start process}. Real executables such as
     * {@code uvx.exe} / {@code python.exe} / {@code java.exe} are passed
     * through unchanged. On non-Windows platforms this is a no-op.
     */
    private static String resolveCommand(String command) {
        if (command == null) {
            return null;
        }
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.startsWith("windows")) {
            return command;
        }
        return switch (command) {
            case "npx", "npm", "yarn", "pnpm" -> command + ".cmd";
            default -> command;
        };
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
        return convertObjectSchema(schemaMap);
    }

    /**
     * Convert a JSON Schema object node into a langchain4j {@link JsonObjectSchema}.
     * Handles {@code properties} (per-property type-aware conversion via
     * {@link #convertElement}), {@code required}, top-level {@code description},
     * and {@code additionalProperties}. Used for both the tool root schema and
     * any nested object property.
     */
    @SuppressWarnings("unchecked")
    private static JsonObjectSchema convertObjectSchema(Map<String, Object> schemaMap) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        Object desc = schemaMap.get("description");
        if (desc != null) {
            builder.description(String.valueOf(desc));
        }
        Object propsObj = schemaMap.get("properties");
        if (propsObj instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                String propName = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> propSpec) {
                    JsonSchemaElement propSchema = convertElement((Map<String, Object>) propSpec);
                    builder.addProperty(propName, propSchema);
                }
            }
        }
        Object reqObj = schemaMap.get("required");
        if (reqObj instanceof List<?> req && !req.isEmpty()) {
            builder.required(req.stream().map(String::valueOf).toArray(String[]::new));
        }
        Object addProps = schemaMap.get("additionalProperties");
        if (addProps instanceof Boolean b) {
            builder.additionalProperties(b);
        }
        return builder.build();
    }

    /**
     * Convert a single JSON Schema property/element node into the matching
     * langchain4j {@link JsonSchemaElement}. Resolution order matters:
     * <ol>
     *   <li>Composition keywords ({@code oneOf} / {@code anyOf} / {@code allOf})
     *       collapse into {@link JsonAnyOfSchema} — strict {@code allOf} merging
     *       isn't supported by langchain4j 1.1, so we treat it as anyOf for
     *       LLM-facing purposes (the server still validates strictly).</li>
     *   <li>{@code enum} without explicit type → {@link JsonEnumSchema} with
     *       string values (JSON Schema's default).</li>
     *   <li>Multi-type {@code "type": ["string", "boolean"]} → {@link JsonAnyOfSchema}
     *       wrapping each type's bare schema.</li>
     *   <li>Single type → matching primitive schema. Arrays recurse on
     *       {@code items}, objects recurse on {@link #convertObjectSchema}.</li>
     *   <li>Unknown / missing type → {@link JsonStringSchema} fallback so the
     *       LLM still sees the description, never a blank parameter.</li>
     * </ol>
     * <p>This replaces the earlier "everything is a string" simplification that
     * caused real MCP servers (Tavily etc.) to reject calls because boolean /
     * enum-typed parameters arrived as plain strings.
     */
    @SuppressWarnings("unchecked")
    private static JsonSchemaElement convertElement(Map<String, Object> propMap) {
        String description = propMap.get("description") == null ? null : String.valueOf(propMap.get("description"));

        // Composition: oneOf / anyOf / allOf — fold all alternatives into anyOf.
        List<Map<String, Object>> alternatives = extractAlternatives(propMap);
        if (alternatives != null && !alternatives.isEmpty()) {
            List<JsonSchemaElement> subs = alternatives.stream()
                    .map(OfficialSdkMcpClient::convertElement)
                    .toList();
            JsonAnyOfSchema.Builder anyOfBuilder = JsonAnyOfSchema.builder().anyOf(subs);
            if (description != null) {
                anyOfBuilder.description(description);
            }
            return anyOfBuilder.build();
        }

        // Enum without explicit type → string-valued enum (JSON Schema convention).
        if (propMap.get("enum") instanceof List<?> enumValues && !enumValues.isEmpty()
                && !(propMap.get("type") instanceof List<?>)) {
            List<String> stringEnums = enumValues.stream().map(String::valueOf).toList();
            JsonEnumSchema.Builder enumBuilder = JsonEnumSchema.builder().enumValues(stringEnums);
            if (description != null) {
                enumBuilder.description(description);
            }
            return enumBuilder.build();
        }

        Object typeObj = propMap.get("type");

        // Multi-type union ("type": ["string", "boolean"]) → anyOf each type.
        if (typeObj instanceof List<?> typeList && !typeList.isEmpty()) {
            List<JsonSchemaElement> subs = typeList.stream()
                    .map(t -> {
                        Map<String, Object> sub = new LinkedHashMap<>(propMap);
                        sub.put("type", String.valueOf(t));
                        return convertElement(sub);
                    })
                    .toList();
            JsonAnyOfSchema.Builder anyOfBuilder = JsonAnyOfSchema.builder().anyOf(subs);
            if (description != null) {
                anyOfBuilder.description(description);
            }
            return anyOfBuilder.build();
        }

        String type = typeObj == null ? null : String.valueOf(typeObj);
        return switch (type == null ? "" : type) {
            case "string" -> {
                JsonStringSchema.Builder b = JsonStringSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "boolean" -> {
                JsonBooleanSchema.Builder b = JsonBooleanSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "integer" -> {
                JsonIntegerSchema.Builder b = JsonIntegerSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "number" -> {
                JsonNumberSchema.Builder b = JsonNumberSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "array" -> {
                JsonArraySchema.Builder b = JsonArraySchema.builder();
                if (description != null) b.description(description);
                Object itemsObj = propMap.get("items");
                if (itemsObj instanceof Map<?, ?> itemsMap) {
                    b.items(convertElement((Map<String, Object>) itemsMap));
                } else {
                    // items missing or non-object — default to string items so
                    // langchain4j's required-items invariant is satisfied.
                    b.items(JsonStringSchema.builder().build());
                }
                yield b.build();
            }
            case "object" -> convertObjectSchema(propMap);
            default -> {
                // null / unknown type — keep the description visible to the LLM
                // by surfacing as a string property rather than dropping silently.
                JsonStringSchema.Builder b = JsonStringSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
        };
    }

    /**
     * Pull the list of sub-schemas out of {@code oneOf}, {@code anyOf}, or
     * {@code allOf} (whichever appears first). Returns {@code null} if none
     * present, an empty list if the keyword exists but has no entries.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractAlternatives(Map<String, Object> propMap) {
        for (String key : List.of("oneOf", "anyOf", "allOf")) {
            Object alts = propMap.get(key);
            if (alts instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> result = new ArrayList<>(list.size());
                for (Object alt : list) {
                    if (alt instanceof Map<?, ?> altMap) {
                        result.add((Map<String, Object>) altMap);
                    }
                }
                return result;
            }
        }
        return null;
    }
}
