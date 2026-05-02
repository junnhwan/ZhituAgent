package com.zhituagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.context.ContextBundle;
import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.orchestrator.AgentLoop;
import com.zhituagent.orchestrator.ToolCallExecutor;
import com.zhituagent.rag.DocumentSplitter;
import com.zhituagent.rag.KnowledgeIngestService;
import com.zhituagent.rag.RagRetriever;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import com.zhituagent.trace.Span;
import com.zhituagent.trace.SpanCollector;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentOrchestratorTest {

    private static final String ALERT = """
            {"alertname":"HighCPUUsage","service":"order-service","value":92}
            """;

    @Test
    void shouldStopImmediatelyWhenSupervisorReturnsFinishOnFirstRound() {
        Fixture fx = new Fixture();
        fx.supervisor.queue("{\"next\":\"FINISH\",\"reason\":\"nothing to do\"}");

        MultiAgentResult result = fx.orchestrator().run(ALERT, Map.of(), 5);

        assertThat(result.agentTrail()).isEmpty();
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.reachedMaxRounds()).isFalse();
        assertThat(result.finalAnswer()).contains("supervisor finished without invoking");
        assertThat(result.supervisorDecisions()).hasSize(1);
        assertThat(result.supervisorDecisions().get(0).next()).isEqualTo("FINISH");
        fx.cleanup();
    }

    @Test
    void shouldRouteTriageThenReportAndFinish() {
        Fixture fx = new Fixture();
        fx.supervisor.queue(
                "{\"next\":\"AlertTriageAgent\",\"reason\":\"start\"}",
                "{\"next\":\"ReportAgent\",\"reason\":\"have triage\"}",
                "{\"next\":\"FINISH\",\"reason\":\"done\"}"
        );
        fx.specialistText.add("triage: CPU spike, runbook step 2 says scale up");
        fx.specialistText.add("# 报告\n## 根因\nCPU 飙升");

        MultiAgentResult result = fx.orchestrator().run(ALERT, Map.of(), 5);

        assertThat(result.agentTrail()).containsExactly("AlertTriageAgent", "ReportAgent");
        assertThat(result.finalAnswer()).contains("# 报告");
        assertThat(result.reachedMaxRounds()).isFalse();
        // ReportAgent finishing breaks the loop, so the third (FINISH) supervisor decision never fires.
        assertThat(result.supervisorDecisions()).hasSize(2);
        fx.cleanup();
    }

    @Test
    void shouldRouteTriageLogQueryReportInThreeStepFlow() {
        Fixture fx = new Fixture();
        fx.supervisor.queue(
                "{\"next\":\"AlertTriageAgent\",\"reason\":\"start\"}",
                "{\"next\":\"LogQueryAgent\",\"reason\":\"need logs\"}",
                "{\"next\":\"ReportAgent\",\"reason\":\"compose\"}"
        );
        fx.specialistText.add("triage: need logs to confirm");
        fx.specialistText.add("logs: 5xx spike at 14:02 from order-service");
        fx.specialistText.add("# 报告\n## 根因\nDB connection pool exhausted");

        MultiAgentResult result = fx.orchestrator().run(ALERT, Map.of(), 5);

        assertThat(result.agentTrail()).containsExactly("AlertTriageAgent", "LogQueryAgent", "ReportAgent");
        assertThat(result.finalAnswer()).contains("# 报告");
        assertThat(result.reachedMaxRounds()).isFalse();
        fx.cleanup();
    }

    @Test
    void shouldFallbackToReportAgentWhenSupervisorReturnsInvalidJson() {
        Fixture fx = new Fixture();
        fx.supervisor.queue("not json at all");
        fx.specialistText.add("# 报告\n## 根因\n未知");

        MultiAgentResult result = fx.orchestrator().run(ALERT, Map.of(), 5);

        // Round 1: parseOrFallback → ReportAgent. ReportAgent runs, then loop breaks.
        assertThat(result.agentTrail()).containsExactly("ReportAgent");
        assertThat(result.supervisorDecisions().get(0).next()).isEqualTo("ReportAgent");
        assertThat(result.supervisorDecisions().get(0).reason()).contains("parse error");
        fx.cleanup();
    }

    @Test
    void shouldForceReportAgentWhenSupervisorKeepsRoutingLogQueryUntilMaxRounds() {
        Fixture fx = new Fixture();
        fx.supervisor.queue(
                "{\"next\":\"LogQueryAgent\",\"reason\":\"more logs 1\"}",
                "{\"next\":\"LogQueryAgent\",\"reason\":\"more logs 2\"}",
                "{\"next\":\"LogQueryAgent\",\"reason\":\"more logs 3\"}"
        );
        fx.specialistText.add("logs round 1");
        fx.specialistText.add("logs round 2");
        fx.specialistText.add("logs round 3");
        // Forced ReportAgent run after the safety valve kicks in
        fx.specialistText.add("# 报告\n## 根因\n基于多轮日志:超时");

        MultiAgentResult result = fx.orchestrator().run(ALERT, Map.of(), 3);

        assertThat(result.agentTrail()).hasSize(4);
        assertThat(result.agentTrail().get(result.agentTrail().size() - 1)).isEqualTo("ReportAgent");
        assertThat(result.reachedMaxRounds()).isTrue();
        assertThat(result.rounds()).isEqualTo(3);
        assertThat(result.finalAnswer()).contains("# 报告");
        fx.cleanup();
    }

    @Test
    void shouldInvokeStageCallbackForEachSupervisorAndSpecialistTurn() {
        Fixture fx = new Fixture();
        fx.supervisor.queue(
                "{\"next\":\"AlertTriageAgent\",\"reason\":\"start\"}",
                "{\"next\":\"LogQueryAgent\",\"reason\":\"need logs\"}",
                "{\"next\":\"ReportAgent\",\"reason\":\"compose\"}"
        );
        fx.specialistText.add("triage");
        fx.specialistText.add("logs");
        fx.specialistText.add("# 报告\n## 根因\nx");

        List<MultiAgentStageEvent> events = new ArrayList<>();
        fx.orchestrator().run(ALERT, Map.of(), 5, events::add);

        // Three rounds = three supervisor-routing emits + three specialist emits.
        assertThat(events).extracting(MultiAgentStageEvent::phase).containsExactly(
                "supervisor-routing",
                "agent",                  // AlertTriageAgent (round 1)
                "supervisor-routing",
                "agent",                  // LogQueryAgent (round 2)
                "supervisor-routing",
                "final-writing"           // ReportAgent (round 3)
        );
        assertThat(events.get(1).agentName()).isEqualTo("AlertTriageAgent");
        assertThat(events.get(3).agentName()).isEqualTo("LogQueryAgent");
        assertThat(events.get(5).agentName()).isEqualTo("ReportAgent");
        fx.cleanup();
    }

    @Test
    void shouldNotFailWhenStageCallbackThrows() {
        Fixture fx = new Fixture();
        fx.supervisor.queue(
                "{\"next\":\"AlertTriageAgent\",\"reason\":\"start\"}",
                "{\"next\":\"ReportAgent\",\"reason\":\"compose\"}"
        );
        fx.specialistText.add("triage");
        fx.specialistText.add("report");

        Consumer<MultiAgentStageEvent> brokenCallback = e -> {
            throw new RuntimeException("broken downstream");
        };

        // Must complete the run without propagating callback exceptions.
        MultiAgentResult result = fx.orchestrator().run(ALERT, Map.of(), 5, brokenCallback);

        assertThat(result.agentTrail()).containsExactly("AlertTriageAgent", "ReportAgent");
        fx.cleanup();
    }

    @Test
    void shouldEmitNestedSpansForSupervisorAndSpecialistTurns() {
        Fixture fx = new Fixture();
        fx.supervisor.queue(
                "{\"next\":\"AlertTriageAgent\",\"reason\":\"start\"}",
                "{\"next\":\"ReportAgent\",\"reason\":\"compose\"}"
        );
        fx.specialistText.add("triage done");
        fx.specialistText.add("report done");

        fx.orchestrator().run(ALERT, Map.of(), 5);

        List<Span> spans = fx.spanCollector.drain();
        // Recreate trace context for cleanup
        fx.spanCollector.beginTrace();

        assertThat(spans).extracting(Span::name)
                .contains("multi-agent.run", "agent.supervisor",
                        "agent.specialist.AlertTriageAgent",
                        "agent.specialist.ReportAgent");
    }

    private static final class Fixture {
        final SupervisorStubLlm supervisor = new SupervisorStubLlm();
        final Deque<String> specialistText = new ArrayDeque<>();
        final SpanCollector spanCollector = new SpanCollector();
        final ToolRegistry toolRegistry;
        final ToolCallExecutor toolCallExecutor;
        final AgentLoop agentLoop;
        final RagRetriever ragRetriever;
        final AgentRegistry agentRegistry;
        final RoutingStubLlm sharedLlm;

        Fixture() {
            spanCollector.beginTrace();
            spanCollector.startSpan("chat.turn", "request");
            toolRegistry = new ToolRegistry(List.of(new NoopTool("query_logs"), new NoopTool("query_metrics"), new NoopTool("time")));
            toolCallExecutor = new ToolCallExecutor(toolRegistry);

            // One shared LlmRuntime so the supervisor (generate) and specialists
            // (generateChatTurn) consume from coherent stub state.
            sharedLlm = new RoutingStubLlm(supervisor, specialistText);
            agentLoop = new AgentLoop(sharedLlm, toolRegistry, toolCallExecutor, spanCollector);

            KnowledgeIngestService ingest = new KnowledgeIngestService(new DocumentSplitter());
            ingest.ingest("CPU 飙升 runbook", "1. 检查负载 2. 扩容 3. 回滚最近发布", "HighCPUUsage.md");
            ragRetriever = new RagRetriever(ingest);

            agentRegistry = new AgentRegistry(List.of(
                    new Agent("AlertTriageAgent", "triage", "you are triage", Set.of(), null),
                    new Agent("LogQueryAgent", "logs", "you are logs",
                            Set.of("query_logs", "query_metrics", "time"), null),
                    new Agent("ReportAgent", "report", "you are report", Set.of(), null)
            ));
        }

        MultiAgentOrchestrator orchestrator() {
            return new MultiAgentOrchestrator(
                    agentRegistry, agentLoop, ragRetriever, sharedLlm,
                    spanCollector, new ObjectMapper()
            );
        }

        void cleanup() {
            spanCollector.drain();
        }
    }

    private static final class SupervisorStubLlm {
        private final Deque<String> queue = new ArrayDeque<>();

        void queue(String... responses) {
            for (String r : responses) {
                queue.addLast(r);
            }
        }

        String next() {
            if (queue.isEmpty()) {
                throw new IllegalStateException("supervisor stub queue exhausted");
            }
            return queue.removeFirst();
        }
    }

    private static final class RoutingStubLlm implements LlmRuntime {
        private final SupervisorStubLlm supervisor;
        private final Deque<String> specialistText;

        RoutingStubLlm(SupervisorStubLlm supervisor, Deque<String> specialistText) {
            this.supervisor = supervisor;
            this.specialistText = specialistText;
        }

        @Override
        public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
            return supervisor.next();
        }

        @Override
        public void stream(String systemPrompt, List<String> messages, Map<String, Object> metadata,
                           Consumer<String> onToken, Runnable onComplete) {
            onToken.accept("stub");
            onComplete.run();
        }

        @Override
        public ChatTurnResult generateChatTurn(String systemPrompt,
                                               List<ChatMessage> messages,
                                               List<ToolSpecification> tools,
                                               Map<String, Object> metadata) {
            if (specialistText.isEmpty()) {
                throw new IllegalStateException("specialist text queue exhausted");
            }
            return ChatTurnResult.ofText(specialistText.removeFirst());
        }
    }

    private static final class NoopTool implements ToolDefinition {
        private final String name;

        NoopTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public JsonObjectSchema parameterSchema() {
            return JsonObjectSchema.builder().build();
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return new ToolResult(name, true, name + " noop result", Map.of());
        }
    }
}
