package com.zhituagent.mcp;

import com.zhituagent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Discovers MCP-exposed tools at boot and wraps each one as an
 * {@link McpToolAdapter} registered into the global {@link ToolRegistry}. Runs
 * once after Spring's context is fully wired so the late-binding registration
 * lands before any chat request hits the executor.
 *
 * <p>If {@code zhitu.mcp.enabled=false} (default) this component does nothing,
 * keeping production parity with the pre-MCP behaviour. Flip the flag to bring
 * the {@code mock-mcp.*} tools into the LLM's function-calling vocabulary.
 */
@Component
public class McpToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrar.class);

    private final ToolRegistry toolRegistry;
    private final McpProperties properties;
    private final ObjectProvider<List<McpClient>> clientsProvider;

    public McpToolRegistrar(ToolRegistry toolRegistry,
                            McpProperties properties,
                            ObjectProvider<List<McpClient>> clientsProvider) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.clientsProvider = clientsProvider;
    }

    @PostConstruct
    public int registerDiscoveredTools() {
        if (!properties.isEnabled()) {
            log.info("MCP integration disabled, skipping discovery mcp.skip reason=disabled");
            return 0;
        }
        List<McpClient> clients = clientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) {
            log.warn("MCP enabled but no McpClient beans registered mcp.skip reason=no_clients");
            return 0;
        }
        int registered = 0;
        for (McpClient client : clients) {
            try {
                List<McpToolSpec> specs = client.listTools();
                for (McpToolSpec spec : specs) {
                    McpToolAdapter adapter = new McpToolAdapter(client, spec);
                    toolRegistry.register(adapter);
                    registered++;
                    log.info("MCP tool registered mcp.tool.registered server={} tool={}",
                            client.name(), spec.name());
                }
            } catch (RuntimeException exception) {
                log.error("MCP tool listing failed mcp.tool.list_failed server={} message={}",
                        client.name(), exception.getMessage());
            }
        }
        log.info("MCP registrar finished mcp.registrar.completed clientCount={} registered={}",
                clients.size(), registered);
        return registered;
    }
}
