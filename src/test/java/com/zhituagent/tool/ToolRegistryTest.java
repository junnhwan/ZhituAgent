package com.zhituagent.tool;

import com.zhituagent.memory.MemoryService;
import com.zhituagent.memory.MessageSummaryCompressor;
import com.zhituagent.rag.DocumentSplitter;
import com.zhituagent.rag.KnowledgeIngestService;
import com.zhituagent.session.SessionService;
import com.zhituagent.tool.builtin.KnowledgeWriteTool;
import com.zhituagent.tool.builtin.SessionInspectTool;
import com.zhituagent.tool.builtin.TimeTool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    @Test
    void shouldRegisterBuiltInToolsAndExecuteKnowledgeWrite() {
        MemoryService memoryService = new MemoryService(new MessageSummaryCompressor(4, 6));
        SessionService sessionService = new SessionService(memoryService);
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter());

        ToolRegistry registry = new ToolRegistry(List.of(
                new TimeTool(Clock.fixed(Instant.parse("2026-04-27T09:00:00Z"), ZoneId.of("Asia/Shanghai"))),
                new KnowledgeWriteTool(ingestService),
                new SessionInspectTool(sessionService)
        ));

        assertThat(registry.find("time")).isPresent();
        assertThat(registry.find("knowledge-write")).isPresent();
        assertThat(registry.find("session-inspect")).isPresent();

        ToolResult result = registry.find("knowledge-write")
                .orElseThrow()
                .execute(Map.of(
                        "question", "第一版先做什么？",
                        "answer", "第一版先做会话、记忆、RAG、SSE 和 ToolUse。",
                        "sourceName", "project-notes.md"
                ));

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("knowledge stored");
        assertThat(ingestService.search("第一版先做什么？", 3)).isNotEmpty();
    }
}
