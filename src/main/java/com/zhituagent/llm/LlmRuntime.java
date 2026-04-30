package com.zhituagent.llm;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface LlmRuntime {

    String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata);

    void stream(String systemPrompt,
                List<String> messages,
                Map<String, Object> metadata,
                Consumer<String> onToken,
                Runnable onComplete);

    default ChatTurnResult generateWithTools(String systemPrompt,
                                             List<String> messages,
                                             List<ToolSpecification> tools,
                                             Map<String, Object> metadata) {
        return ChatTurnResult.ofText(generate(systemPrompt, messages, metadata));
    }
}
