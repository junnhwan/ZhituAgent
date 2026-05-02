package com.zhituagent.agent;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring-injected directory of all known {@link Agent} specialists. Used by
 * the supervisor to know which workers it can route to, and by the
 * orchestrator to look up an agent by the name returned in supervisor JSON.
 */
@Component
public class AgentRegistry {

    private final Map<String, Agent> byName;

    public AgentRegistry(List<Agent> agents) {
        Map<String, Agent> map = new LinkedHashMap<>();
        for (Agent agent : agents) {
            if (map.put(agent.name(), agent) != null) {
                throw new IllegalStateException("duplicate agent name: " + agent.name());
            }
        }
        this.byName = Collections.unmodifiableMap(map);
    }

    public Optional<Agent> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Agent> all() {
        return List.copyOf(byName.values());
    }

    /**
     * Render the registered specialists as bullet lines for the supervisor
     * system prompt's {@code {specialists}} placeholder.
     */
    public String descriptionsForSupervisor() {
        StringBuilder builder = new StringBuilder();
        for (Agent agent : byName.values()) {
            builder.append("- ").append(agent.name()).append(": ").append(agent.description()).append("\n");
        }
        return builder.toString().stripTrailing();
    }
}
