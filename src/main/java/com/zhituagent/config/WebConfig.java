package com.zhituagent.config;

import com.zhituagent.mcp.McpClient;
import com.zhituagent.mcp.McpProperties;
import com.zhituagent.mcp.MockMcpClient;
import com.zhituagent.mcp.client.OfficialSdkMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties({
        AppProperties.class,
        LlmProperties.class,
        EmbeddingProperties.class,
        RagProperties.class,
        RerankProperties.class,
        EsProperties.class,
        FileProperties.class,
        InfrastructureProperties.class,
        EvalProperties.class,
        TraceArchiveProperties.class,
        McpProperties.class
})
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }

    /**
     * Build the {@link McpClient} list once at boot. Three modes:
     * <ol>
     *   <li>{@code servers} non-empty → instantiate one
     *       {@link OfficialSdkMcpClient} per config entry; init failures are
     *       logged and the bad server is filtered out, the rest still register.</li>
     *   <li>{@code servers} empty → fall back to a single {@link MockMcpClient}
     *       so the demo path keeps working with {@code zhitu.mcp.enabled=true}
     *       alone (no real-server config required).</li>
     *   <li>{@code zhitu.mcp.enabled=false} → bean is not instantiated at all
     *       (via {@link ConditionalOnProperty}).</li>
     * </ol>
     *
     * <p>{@link com.zhituagent.mcp.McpToolRegistrar} consumes the
     * {@code List<McpClient>} via {@code ObjectProvider} and registers each
     * server's tools into {@code ToolRegistry} after Spring is fully wired.
     */
    @Bean
    @ConditionalOnProperty(prefix = "zhitu.mcp", name = "enabled", havingValue = "true")
    public List<McpClient> mcpClients(McpProperties properties) {
        List<McpProperties.ServerConfig> serverConfigs = properties.getServers();
        if (serverConfigs == null || serverConfigs.isEmpty()) {
            log.info("MCP enabled but no servers configured -> using MockMcpClient fallback mcp.fallback.mock");
            return List.of(new MockMcpClient());
        }
        List<McpClient> clients = new ArrayList<>(serverConfigs.size());
        for (McpProperties.ServerConfig cfg : serverConfigs) {
            try {
                clients.add(new OfficialSdkMcpClient(cfg));
                log.info("MCP client ready mcp.client.ready server={} transport={}",
                        cfg.getName(), cfg.getTransport());
            } catch (RuntimeException exception) {
                log.warn("MCP client init skipped mcp.client.skip server={} transport={} reason={}",
                        cfg.getName(), cfg.getTransport(), exception.getMessage());
            }
        }
        if (clients.isEmpty()) {
            log.warn("All configured MCP servers failed to init -> using MockMcpClient fallback mcp.fallback.all_failed");
            return List.of(new MockMcpClient());
        }
        log.info("MCP clients registered count={}", clients.size());
        return clients;
    }
}
