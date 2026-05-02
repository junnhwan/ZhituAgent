package com.zhituagent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRegistryTest {

    @Test
    void shouldExposeRegisteredAgentsByName() {
        AgentRegistry registry = new AgentRegistry(List.of(
                new Agent("A", "desc-a", "prompt-a", Set.of(), null),
                new Agent("B", "desc-b", "prompt-b", Set.of("tool1"), null)
        ));

        assertThat(registry.get("A")).isPresent();
        assertThat(registry.get("B")).isPresent();
        assertThat(registry.get("missing")).isEmpty();
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void shouldRejectDuplicateNames() {
        assertThatThrownBy(() -> new AgentRegistry(List.of(
                new Agent("A", "x", "y", Set.of(), null),
                new Agent("A", "x", "y", Set.of(), null)
        ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
    }

    @Test
    void shouldFormatDescriptionsForSupervisor() {
        AgentRegistry registry = new AgentRegistry(List.of(
                new Agent("A", "desc-a", "p", Set.of(), null),
                new Agent("B", "desc-b", "p", Set.of(), null)
        ));

        String formatted = registry.descriptionsForSupervisor();

        assertThat(formatted).contains("- A: desc-a");
        assertThat(formatted).contains("- B: desc-b");
        assertThat(formatted).doesNotEndWith("\n");
    }

    @Test
    void shouldRejectBlankAgentName() {
        assertThatThrownBy(() -> new Agent(" ", "d", "p", Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDefaultNullAllowedToolNamesToEmpty() {
        Agent agent = new Agent("X", "d", "p", null, null);
        assertThat(agent.allowedToolNames()).isEmpty();
    }
}
