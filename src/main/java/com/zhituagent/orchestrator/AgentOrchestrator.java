package com.zhituagent.orchestrator;

import com.zhituagent.rag.KnowledgeSnippet;
import com.zhituagent.rag.RagRetrievalResult;
import com.zhituagent.rag.RagRetriever;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentOrchestrator {

    private final RagRetriever ragRetriever;
    private final ToolRegistry toolRegistry;

    public AgentOrchestrator(RagRetriever ragRetriever, ToolRegistry toolRegistry) {
        this.ragRetriever = ragRetriever;
        this.toolRegistry = toolRegistry;
    }

    public RouteDecision decide(String userMessage) {
        if (looksLikeTimeQuestion(userMessage) && toolRegistry.find("time").isPresent()) {
            ToolResult toolResult = toolRegistry.find("time")
                    .orElseThrow()
                    .execute(Map.of("query", userMessage));
            return RouteDecision.tool("time", toolResult);
        }

        RagRetrievalResult retrievalResult = ragRetriever.retrieveDetailed(userMessage, 3);
        if (!retrievalResult.snippets().isEmpty()) {
            return RouteDecision.retrieval(retrievalResult);
        }

        return RouteDecision.direct();
    }

    private boolean looksLikeTimeQuestion(String userMessage) {
        return userMessage != null && (
                userMessage.contains("几点") ||
                userMessage.toLowerCase().contains("time")
        );
    }
}
