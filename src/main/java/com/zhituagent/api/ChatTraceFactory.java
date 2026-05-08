package com.zhituagent.api;

import com.zhituagent.api.dto.SnippetInfo;
import com.zhituagent.api.dto.TraceInfo;
import com.zhituagent.context.ContextBundle;
import com.zhituagent.context.TokenEstimator;
import com.zhituagent.orchestrator.RouteDecision;
import com.zhituagent.rag.KnowledgeSnippet;
import com.zhituagent.trace.Span;
import com.zhituagent.trace.SpanCollector;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChatTraceFactory {

    private static final String DEFAULT_PATH = "direct-answer";
    private static final String DEFAULT_RETRIEVAL_MODE = "none";
    private static final String CONTEXT_STRATEGY = "recent-summary";
    private static final String DEFAULT_RERANK_MODEL = "";
    private final TokenEstimator tokenEstimator;
    private final SpanCollector spanCollector;

    public ChatTraceFactory() {
        this(new TokenEstimator(), new SpanCollector());
    }

    public ChatTraceFactory(SpanCollector spanCollector) {
        this(new TokenEstimator(), spanCollector);
    }

    ChatTraceFactory(TokenEstimator tokenEstimator, SpanCollector spanCollector) {
        this.tokenEstimator = tokenEstimator;
        this.spanCollector = spanCollector;
    }

    public TraceInfo create(RouteDecision routeDecision,
                            String requestId,
                            long latencyMs,
                            ContextBundle contextBundle,
                            String outputText) {
        List<KnowledgeSnippet> snippets = routeDecision == null || routeDecision.snippets() == null
                ? List.of()
                : routeDecision.snippets();
        KnowledgeSnippet topSnippet = snippets.isEmpty() ? null : snippets.getFirst();
        List<String> inputMessages = contextBundle == null ? List.of() : contextBundle.modelMessages();
        long inputTokenEstimate = tokenEstimator.estimateMessages(inputMessages);
        long outputTokenEstimate = tokenEstimator.estimateText(outputText);
        List<String> retrievedSources = snippets.stream()
                .map(KnowledgeSnippet::source)
                .toList();
        List<SnippetInfo> retrievedSnippets = snippets.stream()
                .map(s -> new SnippetInfo(
                        s.source(),
                        truncateContent(s.content(), 300),
                        s.score(),
                        s.denseScore(),
                        s.rerankScore()
                ))
                .toList();

        String traceId = spanCollector.currentTraceId();
        List<Span> spans = spanCollector.drain();

        return new TraceInfo(
                routeDecision == null ? DEFAULT_PATH : routeDecision.path(),
                routeDecision != null && routeDecision.retrievalHit(),
                routeDecision != null && routeDecision.toolUsed(),
                resolveRetrievalMode(routeDecision),
                contextBundle == null || contextBundle.contextStrategy() == null || contextBundle.contextStrategy().isBlank()
                        ? CONTEXT_STRATEGY
                        : contextBundle.contextStrategy(),
                requestId == null || requestId.isBlank() ? "-" : requestId,
                Math.max(0, latencyMs),
                snippets.size(),
                topSnippet == null ? "" : topSnippet.source(),
                topSnippet == null ? 0.0 : topSnippet.score(),
                routeDecision == null ? 0 : Math.max(0, routeDecision.retrievalCandidateCount()),
                routeDecision == null || routeDecision.rerankModel() == null ? DEFAULT_RERANK_MODEL : routeDecision.rerankModel(),
                routeDecision == null ? 0.0 : routeDecision.rerankTopScore(),
                contextBundle == null || contextBundle.facts() == null ? 0 : Math.max(0, contextBundle.facts().size()),
                inputTokenEstimate,
                outputTokenEstimate,
                retrievedSources,
                retrievedSnippets,
                traceId == null ? "" : traceId,
                spans
        );
    }

    private String resolveRetrievalMode(RouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.retrievalMode() == null || routeDecision.retrievalMode().isBlank()) {
            return DEFAULT_RETRIEVAL_MODE;
        }
        return routeDecision.retrievalMode();
    }

    private static String truncateContent(String content, int maxLen) {
        if (content == null) return "";
        return content.length() > maxLen ? content.substring(0, maxLen) + "..." : content;
    }
}
