package com.zhituagent.tool.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryLogsToolTest {

    private final SreFixtureLoader fixtureLoader = new SreFixtureLoader(new ObjectMapper());
    private final QueryLogsTool tool = new QueryLogsTool(fixtureLoader);

    @Test
    void shouldReturnAllLogsForKnownService() {
        ToolResult result = tool.execute(Map.of("service", "order-service"));

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("order-service");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.payload().get("entries");
        assertThat(entries).isNotEmpty();
        assertThat(entries).anySatisfy(e -> assertThat(e.get("level")).isEqualTo("WARN"));
    }

    @Test
    void shouldFilterByLevelWarnOnly() {
        ToolResult result = tool.execute(Map.of("service", "order-service", "level", "WARN"));

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.payload().get("entries");
        assertThat(entries).isNotEmpty();
        assertThat(entries).allSatisfy(e -> assertThat(e.get("level")).isEqualTo("WARN"));
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
        assertThat(result.summary()).contains("nonexistent-service");
    }

    @Test
    void shouldReturnEmptyListWhenLevelDoesNotMatch() {
        ToolResult result = tool.execute(Map.of("service", "order-service", "level", "DEBUG"));

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.payload().get("entries");
        assertThat(entries).isEmpty();
    }
}
