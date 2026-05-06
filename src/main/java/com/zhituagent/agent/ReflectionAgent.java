package com.zhituagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.config.LlmProperties;
import com.zhituagent.llm.LlmRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Self-RAG / Reflexion-style scorer: reads an {@code (originalQuery,
 * candidateAnswer, toolsUsed)} tuple and asks an LLM to grade the answer
 * 1-10 with reasons.
 *
 * <p><b>Why a scorer (not a critic that rewrites)</b>: the M3 borrow
 * narrative is "self-score >= 8 early-exit", parallel to the Reflexion paper
 * and to the reflection step in LangGraph's plan-and-execute templates.
 * Rewriting the answer in place would couple this class to the answer-
 * generation pipeline; here the scorer is read-only and the {@link
 * ReflectionLoop} owns the retry decision.
 *
 * <p><b>Cost story</b>: by default uses the cheap {@code @Qualifier("fallbackLlm")}
 * bean (e.g. gpt-5.4-mini) — the same mini bean already serving M2 fallback
 * + M1 cheap classifier. Scoring is a constrained-output classification task;
 * the small model is plenty for it.
 *
 * <p><b>Safety</b>: any error / parse failure / timeout returns
 * {@link ReflectionVerdict#skipped}, which the loop treats as
 * {@code acceptable=true} so reflection never <i>blocks</i> the user from
 * getting an answer — the worst case is "no retry happened".
 */
@Component
@ConditionalOnProperty(prefix = "zhitu.llm.agent.reflection", name = "enabled", havingValue = "true")
public class ReflectionAgent {

    private static final Logger log = LoggerFactory.getLogger(ReflectionAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are a strict-but-fair answer reviewer. Read the user query and the
            candidate answer below, and reply with STRICT JSON only — no prose, no
            markdown fences. Schema:
            {"score": <integer 1-10>,
             "reasons": "<1-3 short sentences>",
             "suggested_revision": "<optional hint, can be empty>"}

            Scoring rubric:
            - 10: directly answers, factually grounded, no hallucination, well-structured.
            - 7-9: mostly correct, may miss a minor angle or be slightly verbose.
            - 4-6: partially correct or misses the user's intent.
            - 1-3: wrong, hallucinated, or off-topic.
            """;

    private final LlmRuntime scorerLlm;
    private final int scoreThreshold;

    @Autowired
    public ReflectionAgent(@Qualifier("fallbackLlm") ObjectProvider<LlmRuntime> fallbackProvider,
                           ObjectProvider<LlmRuntime> primaryProvider,
                           LlmProperties llmProperties) {
        // useFallbackModel default = true; only fall back to primary if mini bean isn't present.
        boolean useFallback = llmProperties.getAgent().getReflection().isUseFallbackModel();
        LlmRuntime fallback = fallbackProvider.getIfAvailable();
        if (useFallback && fallback != null) {
            this.scorerLlm = fallback;
        } else {
            this.scorerLlm = primaryProvider.getObject();
        }
        this.scoreThreshold = llmProperties.getAgent().getReflection().getScoreThreshold();
        log.info("reflection agent ready scoreThreshold={} usingMiniBean={}",
                scoreThreshold, useFallback && fallback != null);
    }

    /** Test-friendly constructor injecting an explicit scorer + threshold. */
    public ReflectionAgent(LlmRuntime scorerLlm, int scoreThreshold) {
        this.scorerLlm = scorerLlm;
        this.scoreThreshold = scoreThreshold;
    }

    /**
     * Returns a verdict on the candidate answer. Never throws — all failure
     * paths produce {@link ReflectionVerdict#skipped}.
     */
    public ReflectionVerdict score(String originalQuery,
                                   String candidateAnswer,
                                   List<String> toolsUsed) {
        if (candidateAnswer == null || candidateAnswer.isBlank()) {
            return ReflectionVerdict.skipped("empty answer");
        }
        String userPrompt = """
                USER QUERY:
                %s

                CANDIDATE ANSWER:
                %s

                TOOLS USED IN THIS TURN: %s

                Now produce the JSON verdict.
                """.formatted(originalQuery, candidateAnswer,
                toolsUsed == null || toolsUsed.isEmpty() ? "(none)" : toolsUsed.toString());
        try {
            String raw = scorerLlm.generate(SYSTEM_PROMPT, List.of("USER: " + userPrompt),
                    Map.of("phase", "reflection-score"));
            return parse(raw);
        } catch (RuntimeException exception) {
            log.warn("reflection scorer failed — skipping retry error={}", exception.getMessage());
            return ReflectionVerdict.skipped(exception.getMessage());
        }
    }

    private ReflectionVerdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReflectionVerdict.skipped("empty scorer response");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            int score = node.path("score").asInt(0);
            String reasons = node.path("reasons").asText("");
            String revision = node.path("suggested_revision").asText("");
            boolean acceptable = score >= scoreThreshold;
            return new ReflectionVerdict(score, reasons, revision, acceptable);
        } catch (Exception parseFailure) {
            return ReflectionVerdict.skipped("parse failure: " + parseFailure.getMessage());
        }
    }
}
