package com.zhituagent.agent;

import java.util.List;

/**
 * Outcome of a single multi-agent run. Captures the final answer plus enough
 * structured trail metadata for the {@code AlertController} response,
 * baseline-eval comparisons against {@code expectedAgentSequence}, and the
 * front-end timeline UI.
 */
public record MultiAgentResult(
        String finalAnswer,
        List<String> agentTrail,
        List<SupervisorDecision> supervisorDecisions,
        List<AgentExecution> executions,
        int rounds,
        boolean reachedMaxRounds
) {

    public MultiAgentResult {
        agentTrail = agentTrail == null ? List.of() : List.copyOf(agentTrail);
        supervisorDecisions = supervisorDecisions == null ? List.of() : List.copyOf(supervisorDecisions);
        executions = executions == null ? List.of() : List.copyOf(executions);
    }

    public record SupervisorDecision(int round, String next, String reason, long latencyMs) {
    }

    public record AgentExecution(
            String agentName,
            String output,
            int iterations,
            List<String> toolsUsed,
            long latencyMs
    ) {
        public AgentExecution {
            toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
        }
    }
}
