package com.zhituagent.intent;

/**
 * Outcome of intent classification. The {@code tier} records which layer
 * resolved the request — used for cost-saved telemetry and to expose the
 * routing path in traces.
 */
public record IntentResult(
        IntentLabel label,
        double confidence,
        Tier tier,
        long latencyMs
) {

    public enum Tier {
        /** Resolved by a regex/keyword rule — zero LLM cost, sub-millisecond. */
        RULE,
        /** Resolved by the cheap LLM classifier (e.g. gpt-5.4-mini). */
        CHEAP_LLM,
        /** Cache hit on a previously-classified prompt. */
        CACHE,
        /** Classifier was uncertain or errored — caller should run the existing
         *  expensive routing path unchanged. */
        FALLTHROUGH
    }

    public static IntentResult fallthrough(long latencyMs) {
        return new IntentResult(IntentLabel.FALLTHROUGH, 0.0, Tier.FALLTHROUGH, latencyMs);
    }

    public static IntentResult rule(IntentLabel label, double confidence, long latencyMs) {
        return new IntentResult(label, confidence, Tier.RULE, latencyMs);
    }

    public static IntentResult cheapLlm(IntentLabel label, double confidence, long latencyMs) {
        return new IntentResult(label, confidence, Tier.CHEAP_LLM, latencyMs);
    }

    public IntentResult withTier(Tier newTier) {
        return new IntentResult(label, confidence, newTier, latencyMs);
    }
}
