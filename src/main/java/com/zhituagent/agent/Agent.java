package com.zhituagent.agent;

import java.util.Set;

/**
 * Specialist definition used by the multi-agent supervisor pattern. Each
 * specialist carries its own system prompt and a (possibly empty) tool
 * allowlist; the supervisor routes work to specialists by {@code name}, and
 * the orchestrator passes {@code allowedToolNames} into the underlying agent
 * loop so the LLM only sees tools relevant to its role.
 */
public record Agent(
        String name,
        String description,
        String systemPrompt,
        Set<String> allowedToolNames,
        String modelHint
) {

    public Agent {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("agent name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("agent description must not be blank");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("agent systemPrompt must not be blank");
        }
        allowedToolNames = allowedToolNames == null ? Set.of() : Set.copyOf(allowedToolNames);
    }
}
