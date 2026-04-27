package com.zhituagent.tool.builtin;

import com.zhituagent.rag.KnowledgeIngestService;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class KnowledgeWriteTool implements ToolDefinition {

    private final KnowledgeIngestService knowledgeIngestService;

    public KnowledgeWriteTool(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    @Override
    public String name() {
        return "knowledge-write";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String question = asString(arguments.get("question"));
        String answer = asString(arguments.get("answer"));
        String sourceName = asString(arguments.get("sourceName"));

        knowledgeIngestService.ingest(question, answer, sourceName);
        return new ToolResult(
                name(),
                true,
                "knowledge stored for " + sourceName,
                Map.of(
                        "question", question,
                        "sourceName", sourceName
                )
        );
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
