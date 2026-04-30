package com.zhituagent.orchestrator;

import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallExecutorTest {

    @Test
    void shouldRunMultipleToolsInParallelAndCaptureFailuresAsObservations() throws Exception {
        CountDownLatch fastLatch = new CountDownLatch(1);
        CountDownLatch slowLatch = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> startOrder = new ConcurrentLinkedQueue<>();

        AtomicInteger callCount = new AtomicInteger();
        ToolDefinition fastTool = new RecordingTool("fast-tool", arguments -> {
            startOrder.offer("fast-start");
            // wait for slow tool to also start, proving they ran in parallel
            slowLatch.await(2, TimeUnit.SECONDS);
            callCount.incrementAndGet();
            return new ToolResult("fast-tool", true, "fast ok", Map.of("v", arguments.get("x")));
        });
        ToolDefinition slowTool = new RecordingTool("slow-tool", arguments -> {
            startOrder.offer("slow-start");
            slowLatch.countDown();
            fastLatch.await(2, TimeUnit.SECONDS);
            callCount.incrementAndGet();
            return new ToolResult("slow-tool", true, "slow ok", Map.of());
        });
        ToolDefinition explodingTool = new RecordingTool("boom-tool", arguments -> {
            throw new IllegalStateException("simulated tool failure");
        });

        ToolRegistry registry = new ToolRegistry(List.of(fastTool, slowTool, explodingTool));
        ToolCallExecutor executor = new ToolCallExecutor(registry);

        List<ToolExecutionRequest> calls = List.of(
                ToolExecutionRequest.builder().id("t1").name("fast-tool").arguments("{\"x\":1}").build(),
                ToolExecutionRequest.builder().id("t2").name("slow-tool").arguments("{}").build(),
                ToolExecutionRequest.builder().id("t3").name("boom-tool").arguments("{}").build(),
                ToolExecutionRequest.builder().id("t4").name("ghost-tool").arguments("{}").build()
        );

        // release fast latch shortly after slow has started so both stay live concurrently
        Thread releaser = new Thread(() -> {
            try {
                slowLatch.await(2, TimeUnit.SECONDS);
                fastLatch.countDown();
            } catch (InterruptedException ignored) {
            }
        });
        releaser.setDaemon(true);
        releaser.start();

        List<ToolCallExecutor.ToolExecution> executions = executor.executeAll(calls);

        assertThat(executions).hasSize(4);
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(startOrder).contains("fast-start", "slow-start");

        ToolCallExecutor.ToolExecution boom = byName(executions, "boom-tool");
        assertThat(boom.result().success()).isFalse();
        assertThat(boom.result().summary()).contains("simulated tool failure");

        ToolCallExecutor.ToolExecution ghost = byName(executions, "ghost-tool");
        assertThat(ghost.result().success()).isFalse();
        assertThat(ghost.result().summary()).contains("not registered");

        ToolCallExecutor.ToolExecution fast = byName(executions, "fast-tool");
        assertThat(fast.result().success()).isTrue();
        assertThat(fast.result().payload()).containsEntry("v", 1);

        executor.shutdown();
    }

    @Test
    void shouldReturnEmptyListWhenNoCalls() {
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolCallExecutor executor = new ToolCallExecutor(registry);

        List<ToolCallExecutor.ToolExecution> result = executor.executeAll(List.of());

        assertThat(result).isEmpty();
        executor.shutdown();
    }

    private ToolCallExecutor.ToolExecution byName(List<ToolCallExecutor.ToolExecution> executions, String toolName) {
        return executions.stream()
                .filter(execution -> toolName.equals(execution.request().name()))
                .findFirst()
                .orElseThrow();
    }

    @FunctionalInterface
    private interface ToolBody {
        ToolResult run(Map<String, Object> arguments) throws Exception;
    }

    private static class RecordingTool implements ToolDefinition {
        private final String name;
        private final ToolBody body;

        RecordingTool(String name, ToolBody body) {
            this.name = name;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public JsonObjectSchema parameterSchema() {
            return JsonObjectSchema.builder().build();
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            try {
                return body.run(arguments);
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
    }
}
