package com.zhituagent.orchestrator;

import com.zhituagent.context.ContextBundle;
import com.zhituagent.llm.ChatTurnResult;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolRegistry;
import com.zhituagent.tool.ToolResult;
import com.zhituagent.trace.SpanCollector;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopTest {

    @Test
    void shouldRunMultiTurnLoopUntilLlmReturnsTextAnswer() {
        AtomicInteger turnCounter = new AtomicInteger();
        StubLlm stub = new StubLlm(messages -> {
            int turn = turnCounter.incrementAndGet();
            if (turn == 1) {
                return ChatTurnResult.ofToolCalls(List.of(
                        ToolExecutionRequest.builder().id("tc-1").name("time").arguments("{}").build()
                ));
            }
            // Second turn — observation should be visible
            String lastObservation = messages.stream()
                    .filter(m -> m instanceof ToolExecutionResultMessage)
                    .map(m -> ((ToolExecutionResultMessage) m).text())
                    .reduce((a, b) -> b)
                    .orElse("");
            return ChatTurnResult.ofText("final answer based on observation: " + lastObservation);
        });

        SpanCollector spanCollector = new SpanCollector();
        spanCollector.beginTrace();
        spanCollector.startSpan("chat.turn", "request");

        ToolRegistry registry = new ToolRegistry(List.of(new TimeStubTool()));
        ToolCallExecutor executor = new ToolCallExecutor(registry);
        AgentLoop loop = new AgentLoop(stub, registry, executor, spanCollector);

        ContextBundle bundle = new ContextBundle(
                "system-prompt",
                "",
                List.of(),
                List.of(),
                "现在几点了？",
                List.of("USER: 现在几点了？"),
                "recent-summary"
        );

        AgentLoop.LoopResult result = loop.run("system-prompt", "现在几点了？", bundle, Map.of(), 4);

        assertThat(result.converged()).isTrue();
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.finalAnswer()).contains("final answer").contains("current time is mock-12:00");
        assertThat(result.toolsUsed()).containsExactly("time");
        executor.shutdown();
        spanCollector.drain();
    }

    @Test
    void shouldStopAtMaxItersAndReturnPartialFallback() {
        StubLlm stub = new StubLlm(messages -> ChatTurnResult.ofToolCalls(List.of(
                ToolExecutionRequest.builder().id("tc-loop").name("time").arguments("{}").build()
        )));

        SpanCollector spanCollector = new SpanCollector();
        spanCollector.beginTrace();
        spanCollector.startSpan("chat.turn", "request");

        ToolRegistry registry = new ToolRegistry(List.of(new TimeStubTool()));
        ToolCallExecutor executor = new ToolCallExecutor(registry);
        AgentLoop loop = new AgentLoop(stub, registry, executor, spanCollector);

        ContextBundle bundle = new ContextBundle(
                "system-prompt",
                "",
                List.of(),
                List.of(),
                "keep calling tool",
                List.of("USER: keep calling tool"),
                "recent-summary"
        );

        AgentLoop.LoopResult result = loop.run("system-prompt", "keep calling tool", bundle, Map.of(), 2);

        assertThat(result.converged()).isFalse();
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.finalAnswer()).contains("[reached step limit]");
        executor.shutdown();
        spanCollector.drain();
    }

    @Test
    void shouldOnlyExposeAllowedToolsWhenSubsetGiven() {
        StubLlm stub = new StubLlm(messages -> ChatTurnResult.ofText("done"));

        SpanCollector spanCollector = new SpanCollector();
        spanCollector.beginTrace();
        spanCollector.startSpan("chat.turn", "request");

        ToolRegistry registry = new ToolRegistry(List.of(new TimeStubTool(), new SecondStubTool()));
        ToolCallExecutor executor = new ToolCallExecutor(registry);
        AgentLoop loop = new AgentLoop(stub, registry, executor, spanCollector);

        ContextBundle bundle = new ContextBundle(
                "system-prompt",
                "",
                List.of(),
                List.of(),
                "irrelevant",
                List.of("USER: irrelevant"),
                "recent-summary"
        );

        loop.run("system-prompt", "irrelevant", bundle, Map.of(), 1, Set.of("time"));

        assertThat(stub.lastSpecsSeen)
                .extracting(ToolSpecification::name)
                .containsExactly("time");

        executor.shutdown();
        spanCollector.drain();
    }

    @Test
    void shouldExposeAllToolsWhenAllowedNamesIsNull() {
        StubLlm stub = new StubLlm(messages -> ChatTurnResult.ofText("done"));

        SpanCollector spanCollector = new SpanCollector();
        spanCollector.beginTrace();
        spanCollector.startSpan("chat.turn", "request");

        ToolRegistry registry = new ToolRegistry(List.of(new TimeStubTool(), new SecondStubTool()));
        ToolCallExecutor executor = new ToolCallExecutor(registry);
        AgentLoop loop = new AgentLoop(stub, registry, executor, spanCollector);

        ContextBundle bundle = new ContextBundle(
                "system-prompt",
                "",
                List.of(),
                List.of(),
                "irrelevant",
                List.of("USER: irrelevant"),
                "recent-summary"
        );

        loop.run("system-prompt", "irrelevant", bundle, Map.of(), 1);

        assertThat(stub.lastSpecsSeen)
                .extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("time", "second");

        executor.shutdown();
        spanCollector.drain();
    }

    @Test
    void shouldHydrateFactsBlockAsSystemMessage() {
        AtomicReference<List<ChatMessage>> capturedMessages = new AtomicReference<>();
        StubLlm stub = new StubLlm(messages -> {
            capturedMessages.set(messages);
            return ChatTurnResult.ofText("ok");
        });

        SpanCollector spanCollector = new SpanCollector();
        spanCollector.beginTrace();
        spanCollector.startSpan("chat.turn", "request");

        ToolRegistry registry = new ToolRegistry(List.of());
        ToolCallExecutor executor = new ToolCallExecutor(registry);
        AgentLoop loop = new AgentLoop(stub, registry, executor, spanCollector);

        ContextBundle bundle = new ContextBundle(
                "system-prompt",
                "summary text",
                List.of(),
                List.of("我叫小智", "我做 Agent 后端"),
                "current question",
                List.of(
                        "SYSTEM: system-prompt",
                        "SUMMARY: summary text",
                        "FACTS: 我叫小智 | 我做 Agent 后端",
                        "USER: current question"
                ),
                "recent-summary-facts"
        );

        loop.run("system-prompt", "current question", bundle, Map.of(), 4);

        List<ChatMessage> messages = capturedMessages.get();
        assertThat(messages).isNotNull();
        assertThat(messages)
                .anyMatch(m -> m instanceof SystemMessage
                        && ((SystemMessage) m).text().contains("Stable user facts:"))
                .anyMatch(m -> m instanceof SystemMessage
                        && ((SystemMessage) m).text().contains("我叫小智"));

        executor.shutdown();
        spanCollector.drain();
    }

    private interface TurnFn {
        ChatTurnResult next(List<ChatMessage> messages);
    }

    private static class StubLlm implements LlmRuntime {
        private final TurnFn fn;
        List<ToolSpecification> lastSpecsSeen = List.of();

        StubLlm(TurnFn fn) {
            this.fn = fn;
        }

        @Override
        public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
            return "stub";
        }

        @Override
        public void stream(String systemPrompt, List<String> messages, Map<String, Object> metadata,
                           Consumer<String> onToken, Runnable onComplete) {
            onToken.accept("stub");
            onComplete.run();
        }

        @Override
        public ChatTurnResult generateChatTurn(String systemPrompt,
                                               List<ChatMessage> messages,
                                               List<ToolSpecification> tools,
                                               Map<String, Object> metadata) {
            this.lastSpecsSeen = tools == null ? List.of() : List.copyOf(tools);
            return fn.next(messages);
        }
    }

    private static class TimeStubTool implements ToolDefinition {
        @Override
        public String name() {
            return "time";
        }

        @Override
        public JsonObjectSchema parameterSchema() {
            return JsonObjectSchema.builder().build();
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return new ToolResult("time", true, "current time is mock-12:00", Map.of("time", "mock-12:00"));
        }
    }

    private static class SecondStubTool implements ToolDefinition {
        @Override
        public String name() {
            return "second";
        }

        @Override
        public JsonObjectSchema parameterSchema() {
            return JsonObjectSchema.builder().build();
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return new ToolResult("second", true, "second tool ran", Map.of());
        }
    }
}
