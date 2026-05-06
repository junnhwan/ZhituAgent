package com.zhituagent.intent;

/**
 * Coarse-grained intent labels emitted by the M1 dual-layer classifier.
 *
 * <p>These map to the existing {@link com.zhituagent.orchestrator.RouteDecision}
 * paths in {@link com.zhituagent.orchestrator.AgentOrchestrator}:
 *
 * <ul>
 *   <li>{@link #TIME_QUERY} / {@link #TOOL_CALL} — skip RAG, go straight to
 *       function-calling tool selection. Saves 1 ES + LLM round trip per
 *       request that obviously needs a tool.
 *   <li>{@link #GREETING} — when confidence is very high (≥0.95), short-circuit
 *       to {@code RouteDecision.direct()} with no LLM call at all. This is the
 *       cheapest path; abuse it only on extremely safe matches.
 *   <li>{@link #RAG_RETRIEVAL} — explicit hint to run the existing RAG path;
 *       behaviorally identical to {@link #FALLTHROUGH} today, but kept as a
 *       distinct label so future routing can short-circuit RAG-only queries
 *       without an LLM tool-selection round trip.
 *   <li>{@link #MULTI_AGENT} — leave to the existing supervisor; do not
 *       short-circuit. Reserved for future multi-agent dispatch hints.
 *   <li>{@link #UNKNOWN} / {@link #FALLTHROUGH} — classifier was uncertain or
 *       errored; existing AgentOrchestrator logic runs unchanged.
 * </ul>
 */
public enum IntentLabel {
    TIME_QUERY,
    GREETING,
    RAG_RETRIEVAL,
    TOOL_CALL,
    MULTI_AGENT,
    UNKNOWN,
    FALLTHROUGH
}
