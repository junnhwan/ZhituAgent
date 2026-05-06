package com.zhituagent.agent;

/**
 * Outcome of {@link ReflectionAgent#score}.
 *
 * @param score 1-10 self-assessed answer quality (higher = better).
 * @param reasons 1-3 short sentences explaining the score; fed back into the
 *                next AgentLoop pass when {@code !acceptable}.
 * @param suggestedRevision optional rewrite hint produced by the reviewer LLM,
 *                         can be empty when the reviewer didn't propose one.
 * @param acceptable derived: {@code score >= scoreThreshold}.
 */
public record ReflectionVerdict(
        int score,
        String reasons,
        String suggestedRevision,
        boolean acceptable
) {

    public ReflectionVerdict {
        reasons = reasons == null ? "" : reasons;
        suggestedRevision = suggestedRevision == null ? "" : suggestedRevision;
        if (score < 0) score = 0;
        if (score > 10) score = 10;
    }

    /** Convenience used when the scoring LLM call itself failed — treat as acceptable to not block answers. */
    public static ReflectionVerdict skipped(String reason) {
        return new ReflectionVerdict(10, "skipped: " + reason, "", true);
    }
}
