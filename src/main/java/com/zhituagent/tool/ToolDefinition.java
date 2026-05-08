package com.zhituagent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

// 统一工具抽象：所有内置工具和 MCP 外部工具都实现此接口，共享同一套执行、
// 参数校验（JsonArgumentValidator）、循环检测（LoopDetector）、HITL 审批和 Trace 记录链路。
// parameterSchema() 返回 JsonObjectSchema，LLM function-calling spec 和参数校验都从这里取。
public interface ToolDefinition {

    String name();

    ToolResult execute(Map<String, Object> arguments);

    default String description() {
        return name();
    }

    default JsonObjectSchema parameterSchema() {
        return JsonObjectSchema.builder().build();
    }

    /**
     * Whether this tool needs human approval before each call. Defaults to {@code false}.
     * Override to {@code true} for tools with side effects the user may want to vet
     * (writes to the knowledge base, outbound mutations, anything that costs money).
     *
     * <p>The {@code ToolCallExecutor} consults this flag before invoking {@link #execute};
     * pending calls are parked in {@code PendingToolCallStore} until the operator approves
     * via the {@code /api/tool-calls/{id}/approve} endpoint.
     */
    default boolean requiresApproval() {
        return false;
    }

    default ToolSpecification toolSpecification() {
        return ToolSpecification.builder()
                .name(name())
                .description(description())
                .parameters(parameterSchema())
                .build();
    }
}
