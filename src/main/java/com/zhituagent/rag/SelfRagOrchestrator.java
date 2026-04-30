package com.zhituagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.config.RagProperties;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.trace.SpanCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Self-RAG / CRAG style retrieval refinement: ask the LLM whether the snippets
 * are sufficient, and if not, take its suggested rewrite and retrieve again,
 * up to {@code zhitu.rag.self-rag-max-rewrites} times. Returns the best-scoring
 * round to the caller, so a degraded rewrite never makes things worse than
 * the original retrieval.
 *
 * <p>Disabled by default. When the LLM fails or returns malformed JSON, falls
 * back to {@code sufficient=true} so a flaky model never traps us in a loop.
 */
@Component
public class SelfRagOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SelfRagOrchestrator.class);

    private static final String SYSTEM_PROMPT = "You are a retrieval critic. Decide whether the snippets answer the user's question. "
            + "Reply ONLY with compact JSON: {\"sufficient\": <true|false>, \"rewrite\": \"<alternate query if not sufficient, else empty>\"}. "
            + "No prose, no markdown, no extra keys.";
    private static final String USER_PROMPT_TEMPLATE = "Question:\n%s\n\nSnippets:\n%s";

    private final RagRetriever ragRetriever;
    private final LlmRuntime llmRuntime;
    private final RagProperties ragProperties;
    private final SpanCollector spanCollector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public SelfRagOrchestrator(RagRetriever ragRetriever,
                               LlmRuntime llmRuntime,
                               RagProperties ragProperties,
                               SpanCollector spanCollector) {
        this.ragRetriever = ragRetriever;
        this.llmRuntime = llmRuntime;
        this.ragProperties = ragProperties;
        this.spanCollector = spanCollector;
    }

    public SelfRagOrchestrator(RagRetriever ragRetriever,
                               LlmRuntime llmRuntime,
                               RagProperties ragProperties) {
        this(ragRetriever, llmRuntime, ragProperties, new SpanCollector());
    }

    public boolean isEnabled() {
        return ragProperties != null && ragProperties.isSelfRagEnabled();
    }

    public RagRetrievalResult retrieveWithRefinement(String userQuery, int limit, RetrievalRequestOptions options) {
        RagRetrievalResult initial = ragRetriever.retrieveDetailed(userQuery, limit, options);
        if (!isEnabled()) {
            return initial;
        }

        int maxRewrites = Math.max(0, ragProperties.getSelfRagMaxRewrites());
        if (maxRewrites == 0) {
            return initial;
        }

        List<RagRetrievalResult> candidates = new ArrayList<>();
        candidates.add(initial);

        String currentQuery = userQuery;
        RagRetrievalResult currentResult = initial;

        for (int iter = 1; iter <= maxRewrites; iter++) {
            String spanId = spanCollector.startSpan(
                    "rag.self_rag.iter",
                    "evaluation",
                    Map.of("iteration", iter, "query", currentQuery)
            );
            SelfRagDecision decision = evaluate(currentQuery, currentResult);
            if (decision.sufficient()) {
                spanCollector.endSpan(spanId, "ok", Map.of("decision", "sufficient"));
                log.info(
                        "self-rag stopped iter={} reason=sufficient queryPreview={}",
                        iter, preview(currentQuery)
                );
                return currentResult;
            }
            if (!decision.hasRewriteSuggestion() || decision.rewrittenQuery().equalsIgnoreCase(currentQuery)) {
                spanCollector.endSpan(spanId, "ok", Map.of("decision", "no_rewrite"));
                log.info(
                        "self-rag stopped iter={} reason=no_rewrite queryPreview={}",
                        iter, preview(currentQuery)
                );
                break;
            }

            String nextQuery = decision.rewrittenQuery();
            log.info(
                    "self-rag rewrite iter={} previous={} next={} reason={}",
                    iter, preview(currentQuery), preview(nextQuery), decision.reason()
            );
            currentResult = ragRetriever.retrieveDetailed(nextQuery, limit, options);
            currentQuery = nextQuery;
            candidates.add(currentResult);
            spanCollector.endSpan(
                    spanId,
                    "ok",
                    Map.of(
                            "decision", "rewrite",
                            "next_query", nextQuery,
                            "next_result_count", currentResult.snippets().size()
                    )
            );
        }

        return pickBest(candidates);
    }

    private SelfRagDecision evaluate(String query, RagRetrievalResult result) {
        if (result == null || result.snippets().isEmpty()) {
            // No snippets in hand → trivially insufficient. Hand back the original
            // query so the orchestrator falls back to "no rewrite" rather than
            // burning an LLM call asking the obvious.
            return SelfRagDecision.insufficient("", "no_snippets");
        }
        try {
            String userPrompt = String.format(
                    USER_PROMPT_TEMPLATE,
                    query == null ? "" : query,
                    formatSnippets(result.snippets())
            );
            String response = llmRuntime.generate(SYSTEM_PROMPT, List.of("USER: " + userPrompt), Map.of(
                    "purpose", "self-rag-evaluate"
            ));
            return parseDecision(response);
        } catch (RuntimeException exception) {
            log.warn("self-rag evaluator failed, treating as sufficient: {}", exception.getMessage());
            return SelfRagDecision.sufficient("evaluator_failure");
        }
    }

    private SelfRagDecision parseDecision(String response) {
        if (response == null || response.isBlank()) {
            return SelfRagDecision.sufficient("blank_response");
        }
        String json = stripMarkdownFence(response.trim());
        try {
            JsonNode node = objectMapper.readTree(json);
            boolean sufficient = node.path("sufficient").asBoolean(true);
            String rewrite = node.path("rewrite").asText("");
            if (sufficient) {
                return SelfRagDecision.sufficient("llm_judged_sufficient");
            }
            return SelfRagDecision.insufficient(rewrite, "llm_suggested_rewrite");
        } catch (Exception parseException) {
            log.warn("self-rag response not valid JSON, treating as sufficient: response={}", preview(response));
            return SelfRagDecision.sufficient("invalid_json");
        }
    }

    private String stripMarkdownFence(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }

    private String formatSnippets(List<KnowledgeSnippet> snippets) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < snippets.size(); i++) {
            KnowledgeSnippet snippet = snippets.get(i);
            buffer.append(i + 1).append(". [")
                    .append(snippet.source()).append("] ")
                    .append(snippet.content()).append('\n');
        }
        return buffer.toString();
    }

    private RagRetrievalResult pickBest(List<RagRetrievalResult> candidates) {
        return candidates.stream()
                .max(Comparator.comparingDouble(SelfRagOrchestrator::scoreOf))
                .orElseGet(() -> candidates.isEmpty() ? null : candidates.get(0));
    }

    private static double scoreOf(RagRetrievalResult result) {
        if (result == null || result.snippets() == null || result.snippets().isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        return result.snippets().getFirst().score();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 64 ? text : text.substring(0, 64) + "...";
    }
}
