package com.zhituagent.mcp;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory MCP server stand-in. Exposes two demo tools — {@code calculator}
 * and {@code weather_lookup} — with proper input schemas so the LLM can call
 * them through the function-calling pipeline. Used for tests and local demos
 * until the official Java MCP SDK is wired in; swap with a real
 * stdio/SSE-backed client when needed.
 */
public class MockMcpClient implements McpClient {

    private final String name;

    public MockMcpClient() {
        this("mock-mcp");
    }

    public MockMcpClient(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<McpToolSpec> listTools() {
        return List.of(
                new McpToolSpec(
                        "calculator",
                        "Evaluate a basic arithmetic expression. Supports + - * / and parentheses. "
                                + "Use when the user asks for a numeric calculation that benefits from a precise tool.",
                        JsonObjectSchema.builder()
                                .addStringProperty("expression", "Arithmetic expression, e.g. '12 * (3 + 4)'.")
                                .required("expression")
                                .additionalProperties(false)
                                .build()
                ),
                new McpToolSpec(
                        "weather_lookup",
                        "Return today's weather snapshot for a given city. Stub data — for demo only. "
                                + "Use when the user asks about current weather conditions.",
                        JsonObjectSchema.builder()
                                .addStringProperty("city", "City name in pinyin or English, e.g. 'Beijing'.")
                                .required("city")
                                .additionalProperties(false)
                                .build()
                )
        );
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments) {
        if (toolName == null) {
            return McpCallResult.error("toolName must not be null");
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        return switch (toolName) {
            case "calculator" -> handleCalculator(args);
            case "weather_lookup" -> handleWeather(args);
            default -> McpCallResult.error("unknown mcp tool: " + toolName);
        };
    }

    private McpCallResult handleCalculator(Map<String, Object> args) {
        String expression = String.valueOf(args.getOrDefault("expression", "")).trim();
        if (expression.isEmpty()) {
            return McpCallResult.error("expression is required");
        }
        try {
            double value = ArithmeticEvaluator.evaluate(expression);
            String formatted = formatNumber(value);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("expression", expression);
            meta.put("value", value);
            return McpCallResult.ok(expression + " = " + formatted, meta);
        } catch (RuntimeException exception) {
            return McpCallResult.error("could not evaluate '" + expression + "': " + exception.getMessage());
        }
    }

    private McpCallResult handleWeather(Map<String, Object> args) {
        String city = String.valueOf(args.getOrDefault("city", "")).trim();
        if (city.isEmpty()) {
            return McpCallResult.error("city is required");
        }
        // Deterministic stub: hash city to a stable temperature/condition pair so
        // tests can assert the exact output without flakiness.
        int hash = Math.abs(city.toLowerCase().hashCode());
        int tempC = 10 + (hash % 20);
        String[] conditions = {"sunny", "cloudy", "light rain", "windy", "overcast"};
        String condition = conditions[hash % conditions.length];
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("city", city);
        meta.put("date", LocalDate.now().toString());
        meta.put("temperatureCelsius", tempC);
        meta.put("condition", condition);
        return McpCallResult.ok(
                String.format("Weather in %s today: %d°C, %s.", city, tempC, condition),
                meta
        );
    }

    private static String formatNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
