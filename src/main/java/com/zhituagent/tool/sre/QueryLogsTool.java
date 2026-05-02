package com.zhituagent.tool.sre;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class QueryLogsTool implements ToolDefinition {

    private final SreFixtureLoader fixtureLoader;

    public QueryLogsTool(SreFixtureLoader fixtureLoader) {
        this.fixtureLoader = fixtureLoader;
    }

    @Override
    public String name() {
        return "query_logs";
    }

    @Override
    public String description() {
        return "Fetch recent application logs for a given service from the SRE log store. "
                + "Use this when investigating an alert and you need to see error / warn messages "
                + "or request traces. Optional level filter narrows to INFO, WARN, or ERROR.";
    }

    @Override
    public JsonObjectSchema parameterSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("service", "Service name, e.g. order-service / payment-service / user-service.")
                .addStringProperty("level", "Optional level filter: INFO, WARN, or ERROR. If omitted, return all levels.")
                .required("service")
                .additionalProperties(false)
                .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String service = stringArg(arguments, "service");
        String level = stringArg(arguments, "level").toUpperCase();

        if (service.isBlank()) {
            return new ToolResult(name(), false,
                    "missing required argument: service",
                    Map.of());
        }

        Optional<JsonNode> doc = fixtureLoader.loadJson("sre-fixtures/logs/" + service + ".json");
        if (doc.isEmpty()) {
            return new ToolResult(name(), false,
                    "no log fixture available for service=" + service,
                    Map.of("service", service));
        }

        JsonNode entries = doc.get().path("entries");
        List<Map<String, Object>> filtered = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        summary.append("Logs for ").append(service);
        if (!level.isBlank()) {
            summary.append(" (level=").append(level).append(")");
        }
        summary.append(":");

        for (JsonNode entry : entries) {
            String entryLevel = entry.path("level").asText("");
            if (!level.isBlank() && !entryLevel.equalsIgnoreCase(level)) {
                continue;
            }
            String ts = entry.path("timestamp").asText("");
            String msg = entry.path("message").asText("");
            summary.append("\n- [").append(ts).append("] ")
                    .append(entryLevel).append(": ").append(msg);

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("timestamp", ts);
            e.put("level", entryLevel);
            e.put("message", msg);
            filtered.add(e);
        }

        if (filtered.isEmpty()) {
            return new ToolResult(name(), true,
                    "no logs matched filter for service=" + service + " level=" + level,
                    Map.of("service", service, "level", level, "entries", List.of()));
        }

        return new ToolResult(name(), true,
                summary.toString(),
                Map.of("service", service, "level", level, "entries", filtered));
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
