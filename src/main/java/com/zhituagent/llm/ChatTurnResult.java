package com.zhituagent.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

public record ChatTurnResult(
        String text,
        List<ToolExecutionRequest> toolCalls
) {

    public ChatTurnResult {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public static ChatTurnResult ofText(String text) {
        return new ChatTurnResult(text, List.of());
    }

    public static ChatTurnResult ofToolCalls(List<ToolExecutionRequest> toolCalls) {
        return new ChatTurnResult("", toolCalls);
    }
}
