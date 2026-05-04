package com.zhituagent.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "zhitu.mcp")
public class McpProperties {

    private boolean enabled = false;
    /**
     * @deprecated Legacy single-transport hint. Kept for backwards compatibility
     * with {@code mock} demos. Real MCP servers configure transport per-server
     * via {@link #servers}.
     */
    @Deprecated
    private String transport = "mock";

    /**
     * Real MCP server connection configs. Empty list (default) keeps the
     * {@link MockMcpClient} fallback path intact when {@link #enabled} is true.
     * Each entry becomes one {@code OfficialSdkMcpClient} bean instance.
     */
    private List<ServerConfig> servers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public List<ServerConfig> getServers() {
        return servers;
    }

    public void setServers(List<ServerConfig> servers) {
        this.servers = servers == null ? new ArrayList<>() : servers;
    }

    /**
     * One real MCP server's connection params. Most fields are transport-specific:
     * <ul>
     *   <li>stdio: {@link #command}, {@link #args}, {@link #env}</li>
     *   <li>streamable-http: {@link #url}, optionally {@link #headers} for auth /
     *       custom request headers (e.g. {@code Authorization: Bearer ...} for
     *       hosted MCP servers like Baidu Qianfan)</li>
     * </ul>
     */
    public static class ServerConfig {
        /** Logical server name (used as tool prefix and in trace attributes). */
        private String name;
        /** {@code stdio} (default) or {@code streamable-http}. */
        private String transport = "stdio";
        /** stdio: executable to spawn (e.g. {@code npx}, {@code uvx}). */
        private String command;
        /** stdio: command-line args. */
        private List<String> args = new ArrayList<>();
        /** stdio: environment variables passed to the subprocess (API keys etc.). */
        private Map<String, String> env = new LinkedHashMap<>();
        /** streamable-http: server endpoint URL. */
        private String url;
        /**
         * streamable-http: HTTP request headers injected on every JSON-RPC call
         * via {@code McpSyncHttpClientRequestCustomizer}. Typically used for
         * {@code Authorization: Bearer ${TOKEN}} on hosted MCP servers.
         * Ignored on stdio transport.
         */
        private Map<String, String> headers = new LinkedHashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args == null ? new ArrayList<>() : args;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env == null ? new LinkedHashMap<>() : env;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
        }
    }
}
