package com.zhituagent.orchestrator;

import com.zhituagent.config.AppProperties;
import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.rag.RagRetrievalResult;
import com.zhituagent.rag.RagRetriever;
import com.zhituagent.rag.RetrievalMode;
import com.zhituagent.rag.RetrievalRequestOptions;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ARG_MAP_TYPE = new TypeReference<>() {
    };

    private final RagRetriever ragRetriever;
    private final ToolRegistry toolRegistry;
    private final LlmRuntime llmRuntime;
    private final String systemPrompt;

    @Autowired
    public AgentOrchestrator(RagRetriever ragRetriever,
                             ToolRegistry toolRegistry,
                             LlmRuntime llmRuntime,
                             AppProperties appProperties,
                             ResourceLoader resourceLoader) throws IOException {
        this.ragRetriever = ragRetriever;
        this.toolRegistry = toolRegistry;
        this.llmRuntime = llmRuntime;
        Resource resource = resourceLoader.getResource(appProperties.getSystemPromptLocation());
        this.systemPrompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }

    AgentOrchestrator(RagRetriever ragRetriever,
                      ToolRegistry toolRegistry,
                      LlmRuntime llmRuntime,
                      String systemPrompt) {
        this.ragRetriever = ragRetriever;
        this.toolRegistry = toolRegistry;
        this.llmRuntime = llmRuntime;
        this.systemPrompt = systemPrompt;
    }

    public RouteDecision decide(String userMessage) {
        return decide(userMessage, RetrievalRequestOptions.defaults());
    }

    public RouteDecision decide(String userMessage, RetrievalMode retrievalMode) {
        return decide(userMessage, RetrievalRequestOptions.withMode(retrievalMode));
    }

    public RouteDecision decide(String userMessage, RetrievalRequestOptions retrievalOptions) {
        RagRetrievalResult retrievalResult = ragRetriever.retrieveDetailed(userMessage, 3, retrievalOptions);
        if (!retrievalResult.snippets().isEmpty()) {
            return RouteDecision.retrieval(retrievalResult);
        }

        List<ToolSpecification> specs = toolRegistry.specifications();
        if (specs.isEmpty()) {
            return RouteDecision.direct();
        }

        ChatTurnResult turn = llmRuntime.generateWithTools(
                systemPrompt,
                List.of("USER: " + userMessage),
                specs,
                Map.of("phase", "tool-selection")
        );
        if (!turn.hasToolCalls()) {
            return RouteDecision.direct();
        }

        ToolExecutionRequest request = turn.toolCalls().get(0);
        return executeToolCall(request);
    }

    private RouteDecision executeToolCall(ToolExecutionRequest request) {
        String toolName = request.name();
        ToolDefinition tool = toolRegistry.find(toolName).orElse(null);
        if (tool == null) {
            log.warn("LLM 选择了未注册工具 chat.tool.unknown name={} arguments={}", toolName, request.arguments());
            return RouteDecision.direct();
        }
        Map<String, Object> arguments = parseArguments(request.arguments());
        try {
            ToolResult result = tool.execute(arguments);
            return RouteDecision.tool(toolName, result);
        } catch (RuntimeException exception) {
            log.error("工具执行失败 chat.tool.failed name={} message={}", toolName, exception.getMessage());
            ToolResult failure = new ToolResult(toolName, false, "tool execution failed: " + exception.getMessage(), Map.of());
            return RouteDecision.tool(toolName, failure);
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
}
