package com.zhituagent.agent;

import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ReflectionAgentTest {

    @Test
    void score9PassesAcceptableTrueAtThreshold8() {
        StubLlm stub = StubLlm.respond("{\"score\":9,\"reasons\":\"clear and grounded\"}");
        ReflectionAgent agent = new ReflectionAgent(stub, 8);

        ReflectionVerdict verdict = agent.score("query", "good answer", List.of());

        assertThat(verdict.score()).isEqualTo(9);
        assertThat(verdict.acceptable()).isTrue();
        assertThat(verdict.reasons()).contains("clear");
    }

    @Test
    void score5MarksUnacceptableAndCarriesReasons() {
        StubLlm stub = StubLlm.respond(
                "{\"score\":5,\"reasons\":\"missed user intent\",\"suggested_revision\":\"address X\"}");
        ReflectionAgent agent = new ReflectionAgent(stub, 8);

        ReflectionVerdict verdict = agent.score("query", "weak answer", List.of("time"));

        assertThat(verdict.score()).isEqualTo(5);
        assertThat(verdict.acceptable()).isFalse();
        assertThat(verdict.reasons()).contains("missed");
        assertThat(verdict.suggestedRevision()).contains("address X");
    }

    @Test
    void scorerRuntimeExceptionResolvesToSkippedVerdictWhichIsAcceptable() {
        StubLlm stub = StubLlm.alwaysFailing("scorer down");
        ReflectionAgent agent = new ReflectionAgent(stub, 8);

        ReflectionVerdict verdict = agent.score("query", "answer", List.of());

        // skipped() yields acceptable=true so reflection never blocks user answers.
        assertThat(verdict.acceptable()).isTrue();
        assertThat(verdict.reasons()).contains("skipped");
    }

    @Test
    void unparseableScorerOutputResolvesToSkipped() {
        StubLlm stub = StubLlm.respond("not json at all");
        ReflectionAgent agent = new ReflectionAgent(stub, 8);
        ReflectionVerdict verdict = agent.score("q", "a", List.of());
        assertThat(verdict.acceptable()).isTrue();  // skipped → acceptable
        assertThat(verdict.reasons()).contains("parse");
    }

    @Test
    void emptyAnswerSkippedWithoutCallingScorer() {
        StubLlm stub = StubLlm.respond("ignored");
        ReflectionAgent agent = new ReflectionAgent(stub, 8);

        ReflectionVerdict verdict = agent.score("q", "  ", List.of());

        assertThat(verdict.acceptable()).isTrue();
        assertThat(stub.calls.get()).isZero();
    }

    @Test
    void codeFenceWrappedJsonStillParses() {
        StubLlm stub = StubLlm.respond("```json\n{\"score\":7,\"reasons\":\"ok\"}\n```");
        ReflectionAgent agent = new ReflectionAgent(stub, 8);
        ReflectionVerdict verdict = agent.score("q", "a", List.of());
        assertThat(verdict.score()).isEqualTo(7);
        assertThat(verdict.acceptable()).isFalse();  // 7 < 8
    }

    @Test
    void scoreClampedToZeroToTenRange() {
        StubLlm stub = StubLlm.respond("{\"score\":15,\"reasons\":\"x\"}");
        ReflectionAgent agent = new ReflectionAgent(stub, 8);
        ReflectionVerdict v = agent.score("q", "a", List.of());
        assertThat(v.score()).isEqualTo(10);  // clamped
    }

    private static final class StubLlm implements LlmRuntime {
        final AtomicInteger calls = new AtomicInteger();
        private final String response;
        private final RuntimeException failure;

        private StubLlm(String r, RuntimeException f) { this.response = r; this.failure = f; }
        static StubLlm respond(String r) { return new StubLlm(r, null); }
        static StubLlm alwaysFailing(String m) { return new StubLlm(null, new RuntimeException(m)); }

        @Override
        public String generate(String s, List<String> m, Map<String, Object> meta) {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            return response;
        }
        @Override public void stream(String s, List<String> m, Map<String, Object> meta,
                                     Consumer<String> onToken, Runnable onComplete) {
            throw new UnsupportedOperationException();
        }
        @Override public ChatTurnResult generateWithTools(String s, List<String> m,
                                                          List<ToolSpecification> t, Map<String, Object> meta) {
            return ChatTurnResult.ofText(generate(s, m, meta));
        }
        @Override public ChatTurnResult generateChatTurn(String s, List<ChatMessage> m,
                                                         List<ToolSpecification> t, Map<String, Object> meta) {
            return ChatTurnResult.ofText(generate(s, List.of(), meta));
        }
    }
}
