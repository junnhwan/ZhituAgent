package com.zhituagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.context.ContextBundle;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.orchestrator.AgentLoop;
import com.zhituagent.rag.KnowledgeSnippet;
import com.zhituagent.rag.RagRetriever;
import com.zhituagent.trace.SpanCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Supervisor + specialist multi-agent orchestrator for the SRE alert-analysis
 * pipeline (v3). One supervisor LLM turn picks the next worker, the picked
 * worker runs through the existing {@link AgentLoop} (ReAct multi-turn,
 * scoped to its allowlist), then the supervisor decides again — up to
 * {@code maxRounds} routing turns.
 *
 * <p>Design choices:
 * <ul>
 *   <li><b>Selection B for retrieval:</b> the orchestrator pulls runbook
 *       snippets from {@link RagRetriever} once up front and feeds them as
 *       {@code EVIDENCE:} context into the {@code AlertTriageAgent}. The
 *       triage specialist itself has no tools, which keeps its trace clean
 *       and the supervisor's routing surface simple.</li>
 *   <li><b>FINISH only after report:</b> the supervisor system prompt
 *       instructs to choose FINISH only after {@code ReportAgent} has run.
 *       If the model still finishes early without a report, the orchestrator
 *       does NOT force one — that path is reserved for the {@code maxRounds}
 *       safety valve described next.</li>
 *   <li><b>maxRounds safety valve:</b> if the loop exits and at least one
 *       specialist ran but no {@code ReportAgent} was invoked, force a final
 *       {@code ReportAgent} turn so the response always carries a Markdown
 *       summary. {@code reachedMaxRounds} flags this in the result.</li>
 *   <li><b>JSON parse failures fall back, never retry:</b> retry burns tokens
 *       on format-nagging; fallback to {@code ReportAgent} is more reliable
 *       at the multi-agent layer.</li>
 * </ul>
 */
@Component
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);
    private static final String SUPERVISOR_PROMPT_RESOURCE = "/system-prompt/supervisor-sre.txt";
    private static final int DEFAULT_RUNBOOK_RETRIEVAL_LIMIT = 4;
    private static final int DEFAULT_MAX_ROUNDS = 5;
    private static final String REPORT_AGENT_NAME = "ReportAgent";
    private static final String EMPTY_FINAL_ANSWER = "(supervisor finished without invoking any specialist)";

    private final AgentRegistry agentRegistry;
    private final AgentLoop agentLoop;
    private final RagRetriever ragRetriever;
    private final LlmRuntime llmRuntime;
    private final SpanCollector spanCollector;
    private final ObjectMapper objectMapper;
    private final String supervisorPromptTemplate;

    public MultiAgentOrchestrator(AgentRegistry agentRegistry,
                                  AgentLoop agentLoop,
                                  RagRetriever ragRetriever,
                                  LlmRuntime llmRuntime,
                                  SpanCollector spanCollector,
                                  ObjectMapper objectMapper) {
        this.agentRegistry = agentRegistry;
        this.agentLoop = agentLoop;
        this.ragRetriever = ragRetriever;
        this.llmRuntime = llmRuntime;
        this.spanCollector = spanCollector;
        this.objectMapper = objectMapper;
        this.supervisorPromptTemplate = loadResource(SUPERVISOR_PROMPT_RESOURCE);
    }

    public MultiAgentResult run(String alertPayload, Map<String, Object> metadata) {
        return run(alertPayload, metadata, DEFAULT_MAX_ROUNDS, null);
    }

    public MultiAgentResult run(String alertPayload, Map<String, Object> metadata, int maxRounds) {
        return run(alertPayload, metadata, maxRounds, null);
    }

    /**
     * Variant taking a stage callback. Emits {@link MultiAgentStageEvent}s
     * before each supervisor turn and before each specialist turn so that
     * SSE controllers can stream phase markers to the front-end. Pass
     * {@code null} to skip stage emission.
     */
    public MultiAgentResult run(String alertPayload,
                                Map<String, Object> metadata,
                                int maxRounds,
                                Consumer<MultiAgentStageEvent> stageCallback) {
        String runSpan = spanCollector.startSpan("multi-agent.run", "agent", Map.of("maxRounds", maxRounds));
        List<String> agentTrail = new ArrayList<>();
        List<MultiAgentResult.SupervisorDecision> decisions = new ArrayList<>();
        List<MultiAgentResult.AgentExecution> executions = new ArrayList<>();
        String finalAnswer = "";
        int round = 0;
        boolean reachedMaxRounds = false;

        try {
            String runbookContext = retrieveRunbookContext(alertPayload);

            String supervisorPrompt = supervisorPromptTemplate.replace(
                    "{specialists}", agentRegistry.descriptionsForSupervisor()
            );

            while (round < maxRounds) {
                round++;

                emitStage(stageCallback, MultiAgentStageEvent.supervisorRouting(round));

                long supervisorStart = System.currentTimeMillis();
                String supervisorRaw = invokeSupervisor(round, supervisorPrompt, alertPayload, executions, metadata);
                long supervisorLatency = System.currentTimeMillis() - supervisorStart;
                SupervisorTurnResult decision = SupervisorTurnResult.parseOrFallback(
                        supervisorRaw, REPORT_AGENT_NAME, objectMapper);
                decisions.add(new MultiAgentResult.SupervisorDecision(
                        round, decision.next(), decision.reason(), supervisorLatency));

                if ("FINISH".equalsIgnoreCase(decision.next())) {
                    log.info("multi-agent.finished round={} reason={}", round, decision.reason());
                    break;
                }

                Optional<Agent> agentOpt = agentRegistry.get(decision.next());
                String routedTo = decision.next();
                if (agentOpt.isEmpty()) {
                    log.warn("multi-agent.unknown-agent supervisorRouted={} fallback={}",
                            routedTo, REPORT_AGENT_NAME);
                    routedTo = REPORT_AGENT_NAME;
                    agentOpt = agentRegistry.get(REPORT_AGENT_NAME);
                    if (agentOpt.isEmpty()) {
                        log.error("multi-agent.fallback-agent-missing");
                        break;
                    }
                }
                Agent agent = agentOpt.get();

                emitStage(stageCallback, MultiAgentStageEvent.specialist(round, routedTo));

                AgentLoop.LoopResult execResult = invokeSpecialist(
                        agent, alertPayload, runbookContext, executions, metadata);
                finalAnswer = execResult.finalAnswer();
                agentTrail.add(routedTo);
                executions.add(new MultiAgentResult.AgentExecution(
                        routedTo,
                        finalAnswer,
                        execResult.iterations(),
                        execResult.toolsUsed(),
                        0L
                ));

                if (REPORT_AGENT_NAME.equals(routedTo)) {
                    break;
                }
            }

            // Safety valve: at least one specialist ran but no ReportAgent → force one.
            if (!agentTrail.isEmpty() && !agentTrail.contains(REPORT_AGENT_NAME)) {
                log.warn("multi-agent.no-report-after-loop forcing ReportAgent agentTrail={}", agentTrail);
                reachedMaxRounds = true;
                Optional<Agent> reportOpt = agentRegistry.get(REPORT_AGENT_NAME);
                if (reportOpt.isPresent()) {
                    emitStage(stageCallback, MultiAgentStageEvent.specialist(round, REPORT_AGENT_NAME));
                    AgentLoop.LoopResult forced = invokeSpecialist(
                            reportOpt.get(), alertPayload, runbookContext, executions, metadata);
                    finalAnswer = forced.finalAnswer();
                    agentTrail.add(REPORT_AGENT_NAME);
                    executions.add(new MultiAgentResult.AgentExecution(
                            REPORT_AGENT_NAME,
                            finalAnswer,
                            forced.iterations(),
                            forced.toolsUsed(),
                            0L
                    ));
                }
            }

            if (finalAnswer == null || finalAnswer.isBlank()) {
                finalAnswer = EMPTY_FINAL_ANSWER;
            }

        } finally {
            spanCollector.endSpan(runSpan, "ok", Map.of(
                    "rounds", round,
                    "agentCount", agentTrail.size(),
                    "reachedMaxRounds", reachedMaxRounds
            ));
        }

        return new MultiAgentResult(finalAnswer, agentTrail, decisions, executions, round, reachedMaxRounds);
    }

    private static void emitStage(Consumer<MultiAgentStageEvent> callback, MultiAgentStageEvent event) {
        if (callback == null) {
            return;
        }
        try {
            callback.accept(event);
        } catch (RuntimeException e) {
            log.warn("multi-agent.stage-callback-threw phase={} agent={} error={}",
                    event.phase(), event.agentName(), e.getMessage());
        }
    }

    private String invokeSupervisor(int round,
                                    String supervisorPrompt,
                                    String alertPayload,
                                    List<MultiAgentResult.AgentExecution> priorExecutions,
                                    Map<String, Object> metadata) {
        String span = spanCollector.startSpan("agent.supervisor", "llm", Map.of("round", round));
        try {
            List<String> messages = new ArrayList<>();
            messages.add("Alert payload:\n" + alertPayload);
            for (MultiAgentResult.AgentExecution exec : priorExecutions) {
                messages.add("[" + exec.agentName() + " output]\n" + exec.output());
            }
            messages.add("Decide next routing as strict JSON.");
            String raw = llmRuntime.generate(supervisorPrompt, messages, metadata);
            spanCollector.endSpan(span, "ok", Map.of(
                    "rawLength", raw == null ? 0 : raw.length()
            ));
            return raw;
        } catch (RuntimeException e) {
            spanCollector.endSpan(span, "error", Map.of("error", e.getClass().getSimpleName()));
            throw e;
        }
    }

    private AgentLoop.LoopResult invokeSpecialist(Agent agent,
                                                  String alertPayload,
                                                  String runbookContext,
                                                  List<MultiAgentResult.AgentExecution> priorExecutions,
                                                  Map<String, Object> metadata) {
        String span = spanCollector.startSpan(
                "agent.specialist." + agent.name(), "agent", Map.of("agent", agent.name()));
        try {
            List<String> modelMessages = new ArrayList<>();
            modelMessages.add("USER: Alert payload:\n" + alertPayload);
            if ("AlertTriageAgent".equals(agent.name())
                    && runbookContext != null && !runbookContext.isBlank()) {
                modelMessages.add("EVIDENCE: " + runbookContext);
            }
            for (MultiAgentResult.AgentExecution exec : priorExecutions) {
                modelMessages.add("ASSISTANT: [" + exec.agentName() + "]\n" + exec.output());
            }
            String taskInstruction = buildTaskInstruction(agent.name());
            modelMessages.add("USER: " + taskInstruction);

            ContextBundle bundle = new ContextBundle(
                    agent.systemPrompt(),
                    "",
                    List.of(),
                    List.of(),
                    taskInstruction,
                    modelMessages,
                    "multi-agent"
            );

            int specialistMaxIters = "LogQueryAgent".equals(agent.name()) ? 4 : 2;
            AgentLoop.LoopResult result = agentLoop.run(
                    agent.systemPrompt(),
                    taskInstruction,
                    bundle,
                    metadata,
                    specialistMaxIters,
                    agent.allowedToolNames()
            );
            spanCollector.endSpan(span, "ok", Map.of(
                    "iterations", result.iterations(),
                    "answerLength", result.finalAnswer() == null ? 0 : result.finalAnswer().length()
            ));
            return result;
        } catch (RuntimeException e) {
            spanCollector.endSpan(span, "error", Map.of("error", e.getClass().getSimpleName()));
            throw e;
        }
    }

    private String buildTaskInstruction(String agentName) {
        return switch (agentName) {
            case "AlertTriageAgent" -> "Triage the alert above. Reference any retrieved runbook excerpts in EVIDENCE, "
                    + "then state whether log/metric evidence is needed before recommending action.";
            case "LogQueryAgent" -> "Gather log and metric evidence for the affected service in the alert. "
                    + "Use query_logs and query_metrics. Return the 3-5 strongest pieces of evidence.";
            case "ReportAgent" -> "Compose the final SRE Markdown report using prior agents' findings. "
                    + "Sections: 根因 / 影响范围 / 处置建议 / 监控数据.";
            default -> "Continue the work based on the conversation above.";
        };
    }

    private String retrieveRunbookContext(String alertPayload) {
        try {
            List<KnowledgeSnippet> snippets = ragRetriever.retrieve(alertPayload, DEFAULT_RUNBOOK_RETRIEVAL_LIMIT);
            if (snippets == null || snippets.isEmpty()) {
                log.info("multi-agent.runbook-retrieval empty");
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (KnowledgeSnippet s : snippets) {
                sb.append("--- ").append(s.source())
                        .append(" (score=").append(String.format("%.3f", s.score())).append(")\n")
                        .append(s.content()).append("\n");
            }
            return sb.toString().trim();
        } catch (RuntimeException e) {
            log.warn("multi-agent.runbook-retrieval-failed: {}", e.getMessage());
            return "";
        }
    }

    private static String loadResource(String path) {
        try (InputStream input = MultiAgentOrchestrator.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("missing classpath resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load: " + path, e);
        }
    }
}
