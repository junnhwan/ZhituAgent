package com.zhituagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed routing decision from one supervisor LLM turn. Strict JSON contract:
 * {@code {"next": "<agent>|FINISH", "reason": "..."}}.
 *
 * <p>{@link #parseOrFallback(String, String, ObjectMapper)} is tolerant: it
 * tries to extract the JSON object even when wrapped in markdown fences or
 * surrounded by stray prose, and falls back to a caller-supplied agent name
 * (typically {@code ReportAgent}) on any parse failure rather than retrying.
 * In a multi-agent loop a deterministic fallback is more reliable than a
 * retry-until-valid loop that burns tokens.
 */
public record SupervisorTurnResult(String next, String reason) {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*\"next\"[^{}]*\\}", Pattern.DOTALL);

    public SupervisorTurnResult {
        if (next == null || next.isBlank()) {
            throw new IllegalArgumentException("next must not be blank");
        }
        reason = reason == null ? "" : reason;
    }

    public static SupervisorTurnResult parseOrFallback(String raw, String fallbackNext, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return new SupervisorTurnResult(fallbackNext, "empty supervisor response, fallback");
        }
        Matcher matcher = JSON_OBJECT.matcher(raw);
        String candidate = matcher.find() ? matcher.group() : raw.trim();
        try {
            JsonNode node = mapper.readTree(candidate);
            String next = node.path("next").asText("");
            String reason = node.path("reason").asText("");
            if (next.isBlank()) {
                return new SupervisorTurnResult(fallbackNext, "missing 'next' field, fallback");
            }
            return new SupervisorTurnResult(next, reason);
        } catch (Exception e) {
            return new SupervisorTurnResult(fallbackNext, "parse error: " + e.getMessage());
        }
    }
}
