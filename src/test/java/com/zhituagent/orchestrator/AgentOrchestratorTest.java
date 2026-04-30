package com.zhituagent.orchestrator;

import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.rag.DocumentSplitter;
import com.zhituagent.rag.KnowledgeIngestService;
import com.zhituagent.rag.RagRetriever;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.builtin.TimeTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorTest {

    @Test
    void shouldChooseDirectRetrieveAndToolRoutes() {
        KnowledgeIngestService ingestService = new KnowledgeIngestService(new DocumentSplitter());
        ingestService.ingest("第一版先做什么？", "第一版先做会话、记忆、RAG、SSE 和 ToolUse。", "project-notes.md");

        RagRetriever ragRetriever = new RagRetriever(ingestService);
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new TimeTool(Clock.fixed(Instant.parse("2026-04-27T09:00:00Z"), ZoneId.of("Asia/Shanghai")))
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                ragRetriever,
                toolRegistry,
                new TimeKeywordStubLlm(),
                "test-system-prompt"
        );

        RouteDecision directDecision = orchestrator.decide("你好");
        RouteDecision retrieveDecision = orchestrator.decide("第一版先做什么？");
        RouteDecision toolDecision = orchestrator.decide("现在几点了？");
        RouteDecision weekdayDecision = orchestrator.decide("今天星期几？");

        assertThat(directDecision.path()).isEqualTo("direct-answer");
        assertThat(directDecision.toolUsed()).isFalse();
        assertThat(directDecision.retrievalMode()).isEqualTo("none");

        assertThat(retrieveDecision.path()).isEqualTo("retrieve-then-answer");
        assertThat(retrieveDecision.retrievalHit()).isTrue();
        assertThat(retrieveDecision.snippets()).isNotEmpty();
        assertThat(retrieveDecision.retrievalMode()).isEqualTo("dense");
        assertThat(retrieveDecision.retrievalCandidateCount()).isEqualTo(1);

        assertThat(toolDecision.path()).isEqualTo("tool-then-answer");
        assertThat(toolDecision.toolUsed()).isTrue();
        assertThat(toolDecision.toolName()).isEqualTo("time");
        assertThat(toolDecision.retrievalMode()).isEqualTo("none");

        assertThat(weekdayDecision.path()).isEqualTo("tool-then-answer");
        assertThat(weekdayDecision.toolUsed()).isTrue();
        assertThat(weekdayDecision.toolName()).isEqualTo("time");
        assertThat(weekdayDecision.retrievalMode()).isEqualTo("none");
    }

    private static class TimeKeywordStubLlm implements LlmRuntime {

        @Override
        public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
            return "stub-answer";
        }

        @Override
        public void stream(String systemPrompt,
                           List<String> messages,
                           Map<String, Object> metadata,
                           Consumer<String> onToken,
                           Runnable onComplete) {
            onToken.accept("stub-answer");
            onComplete.run();
        }

        @Override
        public ChatTurnResult generateWithTools(String systemPrompt,
                                                List<String> messages,
                                                List<ToolSpecification> tools,
                                                Map<String, Object> metadata) {
            String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1);
            String lower = last.toLowerCase();
            boolean timeIntent = lower.contains("几点") || lower.contains("星期几") || lower.contains("周几")
                    || lower.contains("几号") || lower.contains("日期") || lower.contains("time");
            boolean timeAvailable = tools != null && tools.stream().anyMatch(t -> "time".equals(t.name()));
            if (timeIntent && timeAvailable) {
                return ChatTurnResult.ofToolCalls(List.of(
                        ToolExecutionRequest.builder()
                                .id("stub-1")
                                .name("time")
                                .arguments("{}")
                                .build()
                ));
            }
            return ChatTurnResult.ofText("stub-answer");
        }
    }
}
