package com.zhituagent.llm;

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
}
