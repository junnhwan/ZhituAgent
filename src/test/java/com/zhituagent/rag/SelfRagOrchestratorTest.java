package com.zhituagent.rag;

import com.zhituagent.config.RagProperties;
import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.trace.SpanCollector;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SelfRagOrchestratorTest {

    @Test
    void shouldReturnInitialResultWhenDisabled() {
        RagProperties properties = new RagProperties();
        properties.setSelfRagEnabled(false);
        StubRagRetriever retriever = new StubRagRetriever()
                .withResult("how do I deploy?", resultWithSnippet("deploy-doc", "deploy via Helm"));
        AtomicInteger llmCalls = new AtomicInteger();
        SelfRagOrchestrator sut = new SelfRagOrchestrator(retriever, fixedLlm("garbage", llmCalls), properties);

        RagRetrievalResult result = sut.retrieveWithRefinement("how do I deploy?", 3, RetrievalRequestOptions.defaults());

        assertThat(result.snippets()).hasSize(1);
        assertThat(retriever.callCount()).isEqualTo(1);
        assertThat(llmCalls.get()).isEqualTo(0);
    }

    @Test
    void shouldStopAtFirstRoundWhenLlmJudgesSufficient() {
        RagProperties properties = enabledProperties(2);
        StubRagRetriever retriever = new StubRagRetriever()
                .withResult("question", resultWithSnippet("doc-a", "answer text"));
        AtomicInteger llmCalls = new AtomicInteger();
        SelfRagOrchestrator sut = new SelfRagOrchestrator(
                retriever,
                queuedLlm(llmCalls, "{\"sufficient\":true,\"rewrite\":\"\"}"),
                properties
        );

        RagRetrievalResult result = sut.retrieveWithRefinement("question", 3, RetrievalRequestOptions.defaults());

        assertThat(result.snippets().getFirst().source()).isEqualTo("doc-a");
        assertThat(retriever.callCount()).as("only initial retrieval should run").isEqualTo(1);
        assertThat(llmCalls.get()).isEqualTo(1);
    }

    @Test
    void shouldRewriteQueryAndStopWhenSecondRoundIsSufficient() {
        RagProperties properties = enabledProperties(2);
        StubRagRetriever retriever = new StubRagRetriever()
                .withResult("vague q", resultWithSnippet("noisy-doc", "off-topic"))
                .withResult("specific q", resultWithSnippet("good-doc", "perfect answer", 0.95));
        AtomicInteger llmCalls = new AtomicInteger();
        SelfRagOrchestrator sut = new SelfRagOrchestrator(
                retriever,
                queuedLlm(llmCalls,
                        "{\"sufficient\":false,\"rewrite\":\"specific q\"}",
                        "{\"sufficient\":true,\"rewrite\":\"\"}"),
                properties
        );

        RagRetrievalResult result = sut.retrieveWithRefinement("vague q", 3, RetrievalRequestOptions.defaults());

        assertThat(result.snippets().getFirst().source()).isEqualTo("good-doc");
        assertThat(retriever.callCount()).isEqualTo(2);
        assertThat(llmCalls.get()).isEqualTo(2);
    }

    @Test
    void shouldStopAfterMaxRewritesAndReturnHighestScoringRound() {
        RagProperties properties = enabledProperties(2);
        StubRagRetriever retriever = new StubRagRetriever()
                .withResult("q0", resultWithSnippet("doc-0", "weak", 0.3))
                .withResult("q1", resultWithSnippet("doc-1", "best", 0.9))
                .withResult("q2", resultWithSnippet("doc-2", "decent", 0.6));
        AtomicInteger llmCalls = new AtomicInteger();
        SelfRagOrchestrator sut = new SelfRagOrchestrator(
                retriever,
                queuedLlm(llmCalls,
                        "{\"sufficient\":false,\"rewrite\":\"q1\"}",
                        "{\"sufficient\":false,\"rewrite\":\"q2\"}"),
                properties
        );

        RagRetrievalResult result = sut.retrieveWithRefinement("q0", 3, RetrievalRequestOptions.defaults());

        assertThat(result.snippets().getFirst().source())
                .as("after exhausting rewrites, return the best-scoring round, not the last")
                .isEqualTo("doc-1");
        assertThat(retriever.callCount()).isEqualTo(3);
        assertThat(llmCalls.get()).isEqualTo(2);
    }

    @Test
    void shouldStopWhenLlmReturnsBlankOrEqualRewrite() {
        RagProperties properties = enabledProperties(2);
        StubRagRetriever retriever = new StubRagRetriever()
                .withResult("q", resultWithSnippet("doc", "content"));
        AtomicInteger llmCalls = new AtomicInteger();
        SelfRagOrchestrator sut = new SelfRagOrchestrator(
                retriever,
                queuedLlm(llmCalls, "{\"sufficient\":false,\"rewrite\":\"q\"}"),
                properties
        );

        RagRetrievalResult result = sut.retrieveWithRefinement("q", 3, RetrievalRequestOptions.defaults());

        assertThat(result.snippets().getFirst().source()).isEqualTo("doc");
        assertThat(retriever.callCount()).as("rewrite==input means stop, not loop").isEqualTo(1);
    }

    @Test
    void shouldFallBackToSufficientWhenLlmThrows() {
        RagProperties properties = enabledProperties(2);
        StubRagRetriever retriever = new StubRagRetriever()
                .withResult("q", resultWithSnippet("doc", "content"));
        SelfRagOrchestrator sut = new SelfRagOrchestrator(retriever, throwingLlm(), properties);

        RagRetrievalResult result = sut.retrieveWithRefinement("q", 3, RetrievalRequestOptions.defaults());

        assertThat(result.snippets()).isNotEmpty();
        assertThat(retriever.callCount()).isEqualTo(1);
    }

    @Test
    void shouldFallBackToSufficientWhenLlmReturnsInvalidJson() {
        RagProperties properties = enabledProperties(2);
        StubRagRetriever retriever = new StubRagRetriever()
                .withResult("q", resultWithSnippet("doc", "content"));
        SelfRagOrchestrator sut = new SelfRagOrchestrator(retriever, fixedLlm("not json at all", new AtomicInteger()), properties);

        RagRetrievalResult result = sut.retrieveWithRefinement("q", 3, RetrievalRequestOptions.defaults());

        assertThat(result.snippets()).isNotEmpty();
        assertThat(retriever.callCount()).isEqualTo(1);
    }

    @Test
    void shouldStripMarkdownFenceWhenLlmWrapsJsonInCodeBlock() {
        RagProperties properties = enabledProperties(2);
        StubRagRetriever retriever = new StubRagRetriever()
                .withResult("q", resultWithSnippet("doc", "content"))
                .withResult("rewritten", resultWithSnippet("better-doc", "better content", 0.9));
        AtomicInteger llmCalls = new AtomicInteger();
        SelfRagOrchestrator sut = new SelfRagOrchestrator(
                retriever,
                queuedLlm(llmCalls,
                        "```json\n{\"sufficient\":false,\"rewrite\":\"rewritten\"}\n```",
                        "{\"sufficient\":true,\"rewrite\":\"\"}"),
                properties
        );

        RagRetrievalResult result = sut.retrieveWithRefinement("q", 3, RetrievalRequestOptions.defaults());

        assertThat(result.snippets().getFirst().source()).isEqualTo("better-doc");
    }

    @Test
    void shouldShortCircuitWhenInitialResultIsEmpty() {
        RagProperties properties = enabledProperties(2);
        StubRagRetriever retriever = new StubRagRetriever();
        AtomicInteger llmCalls = new AtomicInteger();
        SelfRagOrchestrator sut = new SelfRagOrchestrator(retriever, queuedLlm(llmCalls), properties);

        RagRetrievalResult result = sut.retrieveWithRefinement("missing", 3, RetrievalRequestOptions.defaults());

        assertThat(result.snippets()).isEmpty();
        assertThat(llmCalls.get()).as("no snippets => skip LLM evaluator").isEqualTo(0);
    }

    private static RagProperties enabledProperties(int maxRewrites) {
        RagProperties properties = new RagProperties();
        properties.setSelfRagEnabled(true);
        properties.setSelfRagMaxRewrites(maxRewrites);
        return properties;
    }

    private static RagRetrievalResult resultWithSnippet(String source, String content) {
        return resultWithSnippet(source, content, 0.5);
    }

    private static RagRetrievalResult resultWithSnippet(String source, String content, double score) {
        return new RagRetrievalResult(
                List.of(new KnowledgeSnippet(source, source + "#0", score, content)),
                "dense",
                1,
                "",
                0.0
        );
    }

    private static LlmRuntime fixedLlm(String response, AtomicInteger callCounter) {
        return new BaseStubLlm() {
            @Override
            public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
                callCounter.incrementAndGet();
                return response;
            }
        };
    }

    private static LlmRuntime queuedLlm(AtomicInteger callCounter, String... responses) {
        Deque<String> queue = new ArrayDeque<>(List.of(responses));
        return new BaseStubLlm() {
            @Override
            public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
                callCounter.incrementAndGet();
                return queue.isEmpty() ? "{\"sufficient\":true,\"rewrite\":\"\"}" : queue.poll();
            }
        };
    }

    private static LlmRuntime throwingLlm() {
        return new BaseStubLlm() {
            @Override
            public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
                throw new RuntimeException("simulated llm timeout");
            }
        };
    }

    private abstract static class BaseStubLlm implements LlmRuntime {
        @Override
        public void stream(String systemPrompt, List<String> messages, Map<String, Object> metadata,
                           Consumer<String> onToken, Runnable onComplete) {
            onToken.accept(generate(systemPrompt, messages, metadata));
            onComplete.run();
        }

        @Override
        public ChatTurnResult generateWithTools(String systemPrompt, List<String> messages,
                                                List<ToolSpecification> tools, Map<String, Object> metadata) {
            return ChatTurnResult.ofText(generate(systemPrompt, messages, metadata));
        }
    }

    private static class StubRagRetriever extends RagRetriever {
        private final Map<String, RagRetrievalResult> resultsByQuery = new HashMap<>();
        private int callCount;

        StubRagRetriever() {
            super(new KnowledgeIngestService(new DocumentSplitter()));
        }

        StubRagRetriever withResult(String query, RagRetrievalResult result) {
            resultsByQuery.put(query, result);
            return this;
        }

        int callCount() {
            return callCount;
        }

        @Override
        public RagRetrievalResult retrieveDetailed(String query, int limit, RetrievalRequestOptions options) {
            callCount++;
            return resultsByQuery.getOrDefault(query,
                    new RagRetrievalResult(List.of(), "dense", 0, "", 0.0));
        }
    }
}
