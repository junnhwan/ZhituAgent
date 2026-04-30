package com.zhituagent.orchestrator;

import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallResilienceTest {

    @Test
    void shouldReturnObservationWhenRequiredArgumentMissing() {
        ToolDefinition strictTool = new SchemaTool();
        ToolRegistry registry = new ToolRegistry(List.of(strictTool));
        ToolCallExecutor executor = new ToolCallExecutor(registry);

        List<ToolCallExecutor.ToolExecution> executions = executor.executeAll(List.of(
                ToolExecutionRequest.builder().id("t1").name("strict-tool").arguments("{}").build()
        ));

        assertThat(executions).hasSize(1);
        ToolResult result = executions.get(0).result();
        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("argument validation failed").contains("missing required property 'sourceName'");
        executor.shutdown();
    }

    @Test
    void shouldRejectUnexpectedAdditionalProperty() {
        ToolDefinition strictTool = new SchemaTool();
        ToolRegistry registry = new ToolRegistry(List.of(strictTool));
        ToolCallExecutor executor = new ToolCallExecutor(registry);

        List<ToolCallExecutor.ToolExecution> executions = executor.executeAll(List.of(
                ToolExecutionRequest.builder()
                        .id("t1")
                        .name("strict-tool")
                        .arguments("{\"sourceName\":\"x\",\"foo\":\"bar\"}")
                        .build()
        ));

        ToolResult result = executions.get(0).result();
        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("unexpected property 'foo'");
        executor.shutdown();
    }

    @Test
    void shouldFlagLoopWhenIdenticalCallRepeats() {
        ToolDefinition strictTool = new SchemaTool();
        ToolRegistry registry = new ToolRegistry(List.of(strictTool));
        ToolCallExecutor executor = new ToolCallExecutor(registry);

        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("t1")
                .name("strict-tool")
                .arguments("{\"sourceName\":\"identical\"}")
                .build();

        ToolResult first = executor.executeAll(List.of(call)).get(0).result();
        ToolResult second = executor.executeAll(List.of(call)).get(0).result();
        ToolResult third = executor.executeAll(List.of(call)).get(0).result();

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(third.success()).isFalse();
        assertThat(third.summary()).contains("tool call loop detected");
        executor.shutdown();
    }

    private static class SchemaTool implements ToolDefinition {
        @Override
        public String name() {
            return "strict-tool";
        }

        @Override
        public JsonObjectSchema parameterSchema() {
            return JsonObjectSchema.builder()
                    .addStringProperty("sourceName", "The source name to record.")
                    .required("sourceName")
                    .additionalProperties(false)
                    .build();
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return new ToolResult(name(), true, "stored " + arguments.get("sourceName"), Map.of());
        }
    }
}
