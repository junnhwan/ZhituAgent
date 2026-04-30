package com.zhituagent.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs multiple LLM-issued tool calls in parallel, capturing failures as
 * {@link ToolResult#success()} = false instead of letting exceptions bubble. The
 * captured failures are returned to the caller so they can be replayed back to
 * the LLM as observations (the "tool error fallback" pattern from the OpenAI
 * cookbook / Anthropic tool-use guide).
 */
@Component
public class ToolCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ARG_MAP_TYPE = new TypeReference<>() {
    };

    private final ToolRegistry toolRegistry;
    private final ExecutorService executor;
    private final LoopDetector loopDetector = new LoopDetector();

    public ToolCallExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.executor = Executors.newFixedThreadPool(4, namedThreadFactory("tool-exec"));
    }

    public List<ToolExecution> executeAll(List<ToolExecutionRequest> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<ToolExecution>> futures = new ArrayList<>(toolCalls.size());
        for (ToolExecutionRequest request : toolCalls) {
            futures.add(CompletableFuture.supplyAsync(() -> executeOne(request), executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();

        List<ToolExecution> results = new ArrayList<>(futures.size());
        for (CompletableFuture<ToolExecution> future : futures) {
            results.add(future.join());
        }
        return List.copyOf(results);
    }

    private ToolExecution executeOne(ToolExecutionRequest request) {
        String name = request.name();
        ToolDefinition tool = toolRegistry.find(name).orElse(null);
        if (tool == null) {
            log.warn("LLM 选择了未注册工具 chat.tool.unknown name={} arguments={}", name, request.arguments());
            ToolResult notFound = new ToolResult(name, false, "tool not registered: " + name, Map.of());
            return new ToolExecution(request, notFound);
        }

        int callCount = loopDetector.record(name, request.arguments());
        if (callCount >= LoopDetector.loopThreshold()) {
            log.warn("工具调用环检测命中 chat.tool.loop_detected name={} count={}", name, callCount);
            ToolResult loop = new ToolResult(
                    name,
                    false,
                    "tool call loop detected: tool '" + name + "' invoked " + callCount
                            + " times with identical arguments. Please change arguments or pick a different tool.",
                    Map.of()
            );
            return new ToolExecution(request, loop);
        }

        Map<String, Object> arguments = parseArguments(request.arguments());

        JsonArgumentValidator.ValidationResult validation = JsonArgumentValidator.validate(
                tool.parameterSchema(),
                arguments
        );
        if (!validation.valid()) {
            log.warn("工具参数 schema 校验失败 chat.tool.schema_violation name={} errors={}", name, validation.errors());
            ToolResult invalid = new ToolResult(
                    name,
                    false,
                    "argument validation failed: " + validation.formatErrors() + ". Please re-issue the call with correct arguments matching the tool schema.",
                    Map.of("validationErrors", validation.errors())
            );
            return new ToolExecution(request, invalid);
        }

        try {
            ToolResult result = tool.execute(arguments);
            return new ToolExecution(request, result);
        } catch (RuntimeException exception) {
            log.error("工具执行失败 chat.tool.failed name={} message={}", name, exception.getMessage());
            ToolResult failure = new ToolResult(
                    name,
                    false,
                    "tool execution failed: " + exception.getMessage(),
                    Map.of()
            );
            return new ToolExecution(request, failure);
        }
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, ARG_MAP_TYPE);
        } catch (Exception exception) {
            log.warn("工具参数 JSON 解析失败 chat.tool.arg_parse_failed raw={} message={}", json, exception.getMessage());
            return new HashMap<>();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    public record ToolExecution(ToolExecutionRequest request, ToolResult result) {
    }
}
