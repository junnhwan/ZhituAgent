package com.zhituagent.agent;

import com.zhituagent.config.LlmProperties;
import com.zhituagent.context.ContextBundle;
import com.zhituagent.orchestrator.AgentLoop;
import com.zhituagent.trace.SpanCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Decorator over {@link AgentLoop} that injects a self-reflection retry step:
 * after the inner loop produces an answer, score it; if the score is below
 * threshold, run the loop one more time with the reviewer's reasons appended
 * to the system prompt.
 *
 * <p><b>Why a wrapper instead of modifying AgentLoop</b>: keeps AgentLoop's
 * core ReAct semantics untouched (and its 4 unit tests byte-stable), and lets
 * callers opt in/out per-feature. ChatService uses the wrapper; the
 * MultiAgentOrchestrator specialist path keeps using AgentLoop directly to
 * preserve current multi-agent behavior — reflection on top of supervisor +
 * specialist routing is a future increment.
 *
 * <p><b>Streaming</b>: reflection requires the full final answer, so the SSE
 * path that bypasses ReflectionLoop is correct by construction —
 * {@link com.zhituagent.api.ChatController}'s streaming branch never calls
 * AgentLoop, only {@code llmRuntime.stream(...)}. No special guard needed
 * here.
 *
 * <p><b>Cost ceiling</b>: max 1 retry × max iterations of AgentLoop, plus
 * 2 mini scoring calls. So worst case = 2× primary loop cost + 2 mini calls.
 * Documented in {@link LlmProperties.Agent.Reflection#getMaxRetries}.
 */
@Component
public class ReflectionLoop {

    private static final Logger log = LoggerFactory.getLogger(ReflectionLoop.class);

    private final AgentLoop agentLoop;
    private final ReflectionAgent reflectionAgent;  // null when feature off
    private final SpanCollector spanCollector;
    private final boolean enabled;
    private final int scoreThreshold;
    private final int maxRetries;

    @Autowired
    public ReflectionLoop(AgentLoop agentLoop,
                          ObjectProvider<ReflectionAgent> reflectionAgentProvider,
                          SpanCollector spanCollector,
                          LlmProperties llmProperties) {
        this.agentLoop = agentLoop;
        this.reflectionAgent = reflectionAgentProvider.getIfAvailable();
        this.spanCollector = spanCollector;
        LlmProperties.Agent.Reflection cfg = llmProperties.getAgent().getReflection();
        this.enabled = cfg.isEnabled();
        this.scoreThreshold = cfg.getScoreThreshold();
        this.maxRetries = cfg.getMaxRetries();
    }

    /** Test-friendly constructor with explicit collaborators. */
    public ReflectionLoop(AgentLoop agentLoop,
                          ReflectionAgent reflectionAgent,
                          SpanCollector spanCollector,
                          boolean enabled,
                          int scoreThreshold,
                          int maxRetries) {
        this.agentLoop = agentLoop;
        this.reflectionAgent = reflectionAgent;
        this.spanCollector = spanCollector;
        this.enabled = enabled;
        this.scoreThreshold = scoreThreshold;
        this.maxRetries = maxRetries;
    }

    public AgentLoop.LoopResult run(String systemPrompt,
                                    String userMessage,
                                    ContextBundle contextBundle,
                                    Map<String, Object> metadata,
                                    int maxIters) {
        return run(systemPrompt, userMessage, contextBundle, metadata, maxIters, null);
    }

    public AgentLoop.LoopResult run(String systemPrompt,
                                    String userMessage,
                                    ContextBundle contextBundle,
                                    Map<String, Object> metadata,
                                    int maxIters,
                                    Set<String> allowedToolNames) {
        AgentLoop.LoopResult firstResult = agentLoop.run(
                systemPrompt, userMessage, contextBundle, metadata, maxIters, allowedToolNames
        );

        if (!enabled || reflectionAgent == null) {
            return firstResult;
        }

        // Score the candidate answer.
        String reflectionSpan = spanCollector.startSpan("agent.reflection", "agent",
                Map.of("answerLength", firstResult.finalAnswer() == null ? 0 : firstResult.finalAnswer().length()));
        ReflectionVerdict verdict;
        try {
            verdict = reflectionAgent.score(userMessage, firstResult.finalAnswer(), firstResult.toolsUsed());
        } finally {
            spanCollector.endSpan(reflectionSpan, "ok");
        }

        if (verdict.acceptable() || maxRetries <= 0) {
            log.info("agent.reflection.accepted score={} threshold={} retried=false",
                    verdict.score(), scoreThreshold);
            return firstResult;
        }

        // One retry with reviewer feedback appended to the system prompt. We
        // intentionally do not pass back the candidate answer — the LLM will
        // re-derive it from scratch with the reviewer feedback as guidance,
        // so retries don't anchor on the bad answer.
        String augmentedPrompt = systemPrompt
                + "\n\n[REVIEWER FEEDBACK on previous attempt — score "
                + verdict.score() + "/10]: " + verdict.reasons()
                + (verdict.suggestedRevision().isBlank()
                    ? "" : "\n[REVIEWER HINT]: " + verdict.suggestedRevision());

        AgentLoop.LoopResult retryResult = agentLoop.run(
                augmentedPrompt, userMessage, contextBundle, metadata, maxIters, allowedToolNames
        );
        log.info("agent.reflection.retried initialScore={} threshold={} retried=true",
                verdict.score(), scoreThreshold);
        return retryResult;
    }
}
