package com.zhituagent.mcp;

import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRegistrarTest {

    @Test
    void shouldSkipDiscoveryWhenDisabled() {
        ToolRegistry registry = new ToolRegistry(List.of());
        McpProperties properties = new McpProperties();
        properties.setEnabled(false);
        McpToolRegistrar registrar = new McpToolRegistrar(registry, properties,
                singletonProvider(List.of(new MockMcpClient())));

        int registered = registrar.registerDiscoveredTools();

        assertThat(registered).isZero();
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void shouldRegisterAllToolsFromAllClientsWhenEnabled() {
        ToolRegistry registry = new ToolRegistry(List.of());
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpToolRegistrar registrar = new McpToolRegistrar(registry, properties,
                singletonProvider(List.of(new MockMcpClient("server-a"), new MockMcpClient("server-b"))));

        int registered = registrar.registerDiscoveredTools();

        assertThat(registered).isEqualTo(4); // 2 tools × 2 servers
        assertThat(registry.names()).contains("server-a__calculator", "server-a__weather_lookup",
                "server-b__calculator", "server-b__weather_lookup");
    }

    @Test
    void shouldNotFailWhenNoClientsBound() {
        ToolRegistry registry = new ToolRegistry(List.of());
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpToolRegistrar registrar = new McpToolRegistrar(registry, properties, emptyProvider());

        int registered = registrar.registerDiscoveredTools();

        assertThat(registered).isZero();
    }

    @Test
    void shouldQualifyToolNamesWithMcpServerName() {
        ToolRegistry registry = new ToolRegistry(List.of());
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpToolRegistrar registrar = new McpToolRegistrar(registry, properties,
                singletonProvider(List.of(new MockMcpClient("special-server"))));

        registrar.registerDiscoveredTools();

        Optional<ToolDefinition> calculator = registry.find("special-server__calculator");
        assertThat(calculator).isPresent();
        assertThat(calculator.get().description()).contains("[mcp:special-server]");
    }

    @Test
    void shouldRunCalculatorThroughAdapter() {
        ToolRegistry registry = new ToolRegistry(List.of());
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        new McpToolRegistrar(registry, properties,
                singletonProvider(List.of(new MockMcpClient()))).registerDiscoveredTools();

        ToolDefinition calculator = registry.find("mock-mcp__calculator").orElseThrow();
        var result = calculator.execute(Map.of("expression", "12 * (3 + 4)"));

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("12 * (3 + 4)").contains("84");
    }

    private static ObjectProvider<List<McpClient>> singletonProvider(List<McpClient> clients) {
        return new TestObjectProvider<>(clients);
    }

    private static ObjectProvider<List<McpClient>> emptyProvider() {
        return new TestObjectProvider<>(null);
    }

    private static final class TestObjectProvider<T> implements ObjectProvider<T> {
        private final T value;

        TestObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            if (value == null) throw new IllegalStateException("no bean");
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return getObject();
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }
    }
}
