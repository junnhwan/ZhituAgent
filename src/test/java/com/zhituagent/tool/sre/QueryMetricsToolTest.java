package com.zhituagent.tool.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryMetricsToolTest {

    private final SreFixtureLoader fixtureLoader = new SreFixtureLoader(new ObjectMapper());
    private final QueryMetricsTool tool = new QueryMetricsTool(fixtureLoader);

    @Test
    void shouldReturnAllMetricsForKnownService() {
        ToolResult result = tool.execute(Map.of("service", "user-service"));

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) result.payload().get("metrics");
        assertThat(metrics).isNotEmpty();
        assertThat(metrics).anySatisfy(m -> assertThat(m.get("name")).isEqualTo("db_pool_active"));
    }

    @Test
    void shouldFilterToSingleMetric() {
        ToolResult result = tool.execute(Map.of(
                "service", "payment-service",
                "metric", "heap_old_gen_percent"));

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) result.payload().get("metrics");
        assertThat(metrics).hasSize(1);
        Map<String, Object> m = metrics.get(0);
        assertThat(m.get("name")).isEqualTo("heap_old_gen_percent");
        assertThat(m.get("status")).isEqualTo("EXCEED");
    }

    @Test
    void shouldReturnFailureWhenServiceMissing() {
        ToolResult result = tool.execute(Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("service");
    }

    @Test
    void shouldReturnFailureWhenServiceFixtureMissing() {
        ToolResult result = tool.execute(Map.of("service", "nonexistent-service"));

        assertThat(result.success()).isFalse();
    }

    @Test
    void shouldReturnEmptyListWhenMetricFilterMatchesNothing() {
        ToolResult result = tool.execute(Map.of(
                "service", "user-service",
                "metric", "nonexistent_metric"));

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) result.payload().get("metrics");
        assertThat(metrics).isEmpty();
    }
}
