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
public class QueryMetricsTool implements ToolDefinition {

    private final SreFixtureLoader fixtureLoader;

    public QueryMetricsTool(SreFixtureLoader fixtureLoader) {
        this.fixtureLoader = fixtureLoader;
    }

    @Override
    public String name() {
        return "query_metrics";
    }

    @Override
    public String description() {
        return "Fetch monitoring metric snapshots for a given service from the SRE metric store. "
                + "Returns current value, baseline, threshold, status (OK / EXCEED / ABNORMAL), and trend. "
                + "Optional metric filter narrows the result to a single metric name (e.g. cpu_percent, "
                + "memory_percent, db_pool_active).";
    }

    @Override
    public JsonObjectSchema parameterSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("service", "Service name, e.g. order-service / payment-service / user-service.")
                .addStringProperty("metric", "Optional specific metric name. If omitted, returns all metrics for the service.")
                .required("service")
                .additionalProperties(false)
                .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String service = stringArg(arguments, "service");
        String metricFilter = stringArg(arguments, "metric");

        if (service.isBlank()) {
            return new ToolResult(name(), false,
                    "missing required argument: service",
                    Map.of());
        }

        Optional<JsonNode> doc = fixtureLoader.loadJson("sre-fixtures/metrics/" + service + ".json");
        if (doc.isEmpty()) {
            return new ToolResult(name(), false,
                    "no metric fixture available for service=" + service,
                    Map.of("service", service));
        }

        JsonNode metrics = doc.get().path("metrics");
        List<Map<String, Object>> filtered = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        summary.append("Metrics for ").append(service);
        if (!metricFilter.isBlank()) {
            summary.append(" (metric=").append(metricFilter).append(")");
        }
        summary.append(":");

        for (JsonNode metric : metrics) {
            String metricName = metric.path("name").asText("");
            if (!metricFilter.isBlank() && !metricName.equalsIgnoreCase(metricFilter)) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", metricName);
            e.put("current", metric.path("current").asDouble(Double.NaN));
            if (metric.has("baseline") && !metric.path("baseline").isNull()) {
                e.put("baseline", metric.path("baseline").asDouble(Double.NaN));
            }
            if (metric.has("threshold") && !metric.path("threshold").isNull()) {
                e.put("threshold", metric.path("threshold").asDouble(Double.NaN));
            }
            e.put("status", metric.path("status").asText("UNKNOWN"));
            e.put("trend", metric.path("trend").asText("unknown"));
            e.put("timestamp", metric.path("timestamp").asText(""));
            filtered.add(e);

            summary.append("\n- ").append(metricName)
                    .append(": current=").append(metric.path("current").asText("?"))
                    .append(", baseline=").append(metric.path("baseline").asText("?"))
                    .append(", status=").append(metric.path("status").asText("?"))
                    .append(", trend=").append(metric.path("trend").asText("?"));
        }

        if (filtered.isEmpty()) {
            return new ToolResult(name(), true,
                    "no metrics matched filter for service=" + service + " metric=" + metricFilter,
                    Map.of("service", service, "metric", metricFilter, "metrics", List.of()));
        }

        return new ToolResult(name(), true,
                summary.toString(),
                Map.of("service", service, "metric", metricFilter, "metrics", filtered));
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
