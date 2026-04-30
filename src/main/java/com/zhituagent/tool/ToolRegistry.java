package com.zhituagent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final Map<String, ToolDefinition> toolsByName;

    public ToolRegistry(List<ToolDefinition> tools) {
        this.toolsByName = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) {
            this.toolsByName.put(tool.name(), tool);
        }
    }

    public synchronized Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    public synchronized List<String> names() {
        return List.copyOf(toolsByName.keySet());
    }

    public synchronized List<ToolSpecification> specifications() {
        return toolsByName.values().stream()
                .map(ToolDefinition::toolSpecification)
                .toList();
    }

    /**
     * Add a tool to the registry at runtime. Used by integrations that discover
     * tools after Spring startup, e.g. the MCP registrar pulling
     * {@code listTools} from a remote server. Replaces any tool with the same
     * {@code name()} so registrations are idempotent on reconnect.
     */
    public synchronized void register(ToolDefinition tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        toolsByName.put(tool.name(), tool);
    }

    public synchronized boolean unregister(String name) {
        return toolsByName.remove(name) != null;
    }
}
