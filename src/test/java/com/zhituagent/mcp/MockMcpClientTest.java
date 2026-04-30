package com.zhituagent.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockMcpClientTest {

    @Test
    void shouldExposeCalculatorAndWeatherTools() {
        MockMcpClient client = new MockMcpClient();

        assertThat(client.listTools()).extracting(McpToolSpec::name)
                .containsExactly("calculator", "weather_lookup");
    }

    @Test
    void shouldEvaluateArithmeticExpression() {
        MockMcpClient client = new MockMcpClient();

        McpCallResult result = client.callTool("calculator", Map.of("expression", "2 + 3 * 4"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("2 + 3 * 4 = 14");
        assertThat(result.metadata()).containsEntry("expression", "2 + 3 * 4");
    }

    @Test
    void shouldHandleParenthesesAndDivision() {
        MockMcpClient client = new MockMcpClient();

        McpCallResult result = client.callTool("calculator", Map.of("expression", "(10 + 6) / 4"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("4"); // 16/4 = 4
    }

    @Test
    void shouldErrorOnDivisionByZero() {
        MockMcpClient client = new MockMcpClient();

        McpCallResult result = client.callTool("calculator", Map.of("expression", "1 / 0"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("division by zero");
    }

    @Test
    void shouldReturnDeterministicWeatherForCity() {
        MockMcpClient client = new MockMcpClient();

        McpCallResult first = client.callTool("weather_lookup", Map.of("city", "Beijing"));
        McpCallResult second = client.callTool("weather_lookup", Map.of("city", "Beijing"));

        assertThat(first.isError()).isFalse();
        assertThat(first.content()).contains("Beijing").contains("°C");
        assertThat(second.metadata()).isEqualTo(first.metadata());
    }

    @Test
    void shouldErrorOnUnknownTool() {
        MockMcpClient client = new MockMcpClient();

        McpCallResult result = client.callTool("unknown", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("unknown mcp tool");
    }

    @Test
    void shouldErrorOnMissingRequiredArgs() {
        MockMcpClient client = new MockMcpClient();

        assertThat(client.callTool("calculator", Map.of()).isError()).isTrue();
        assertThat(client.callTool("weather_lookup", Map.of()).isError()).isTrue();
    }
}
