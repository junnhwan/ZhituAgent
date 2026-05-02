package com.zhituagent.agent;

/**
 * Stage event surfaced by {@link MultiAgentOrchestrator} for callers (SSE
 * controllers, baseline-eval observers, tracing) that want to know when each
 * supervisor decision and specialist execution begins. Designed to map
 * directly to the {@code SseStageEvent} contract on the wire — but this
 * record itself is protocol-agnostic.
 *
 * <p>Phase values:
 * <ul>
 *   <li>{@code supervisor-routing} — about to invoke the routing LLM.</li>
 *   <li>{@code agent} — about to invoke a non-report specialist; {@code agentName} carries which one.</li>
 *   <li>{@code final-writing} — about to invoke {@code ReportAgent}.</li>
 * </ul>
 */
public record MultiAgentStageEvent(String phase, int round, String agentName) {

    public static MultiAgentStageEvent supervisorRouting(int round) {
        return new MultiAgentStageEvent("supervisor-routing", round, null);
    }

    public static MultiAgentStageEvent specialist(int round, String agentName) {
        if ("ReportAgent".equals(agentName)) {
            return new MultiAgentStageEvent("final-writing", round, agentName);
        }
        return new MultiAgentStageEvent("agent", round, agentName);
    }
}
