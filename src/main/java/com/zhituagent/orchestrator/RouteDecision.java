package com.zhituagent.orchestrator;

import com.zhituagent.rag.KnowledgeSnippet;
import com.zhituagent.tool.ToolResult;

import java.util.List;

public record RouteDecision(
        String path,
        boolean retrievalHit,
        boolean toolUsed,
        String toolName,
        ToolResult toolResult,
        List<KnowledgeSnippet> snippets
) {
}
