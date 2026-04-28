package com.zhituagent.context;

import com.zhituagent.memory.ChatMessageRecord;
import com.zhituagent.memory.MemoryService;
import com.zhituagent.memory.MessageSummaryCompressor;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextManagerTest {

    @Test
    void shouldBuildContextBundleWithSummaryRecentMessagesAndCurrentMessage() {
        MemoryService memoryService = new MemoryService(new MessageSummaryCompressor(4, 6));
        memoryService.append("sess_1", "user", "第一轮问题");
        memoryService.append("sess_1", "assistant", "第一轮回答");
        memoryService.append("sess_1", "user", "第二轮问题");
        memoryService.append("sess_1", "assistant", "第二轮回答");
        memoryService.append("sess_1", "user", "第三轮问题");
        memoryService.append("sess_1", "assistant", "第三轮回答");

        ContextManager contextManager = new ContextManager();
        ContextBundle bundle = contextManager.build(
                "你是测试助手",
                memoryService.snapshot("sess_1"),
                "当前问题",
                ""
        );

        assertThat(bundle.systemPrompt()).isEqualTo("你是测试助手");
        assertThat(bundle.summary()).contains("Earlier conversation summary");
        assertThat(bundle.recentMessages()).hasSize(4);
        assertThat(bundle.modelMessages().getFirst()).contains("SYSTEM:");
        assertThat(bundle.modelMessages().get(1)).contains("SUMMARY:");
        assertThat(bundle.modelMessages().getLast()).contains("当前问题");
    }

    @Test
    void shouldIncludeFactsBlockInModelMessages() {
        ContextManager contextManager = new ContextManager();

        ContextBundle bundle = contextManager.build(
                "你是测试助手",
                new com.zhituagent.memory.MemorySnapshot(
                        "已有总结",
                        java.util.List.of(),
                        java.util.List.of("我叫小智", "我在杭州做 Java Agent 后端开发")
                ),
                "结合我的背景给建议",
                ""
        );

        assertThat(bundle.facts()).containsExactly("我叫小智", "我在杭州做 Java Agent 后端开发");
        assertThat(bundle.modelMessages())
                .anyMatch(message -> message.contains("FACTS:"))
                .anyMatch(message -> message.contains("我叫小智"))
                .anyMatch(message -> message.contains("我在杭州做 Java Agent 后端开发"));
    }

    @Test
    void shouldTrimOldestRecentMessagesWhenBudgetExceeded() {
        ContextManager contextManager = new ContextManager(160, 40, 30, 30, 30);

        ContextBundle bundle = contextManager.build(
                "你是测试助手",
                new com.zhituagent.memory.MemorySnapshot(
                        "这是一个比较长的会话总结，主要讨论上下文管理、记忆分层和检索增强策略。",
                        List.of(
                                message("user", "第一轮特别长的问题，主要围绕第一阶段方案和后续演进路线展开。"),
                                message("assistant", "第一轮特别长的回答，解释为什么要先做基础主链和评估基线。"),
                                message("user", "第二轮继续追问长期记忆、事实抽取和上下文预算控制。"),
                                message("assistant", "第二轮继续说明 recent messages、summary 和 facts 的优先级。")
                        ),
                        List.of("我叫小智", "我在杭州做 Java Agent 后端开发")
                ),
                "请结合当前背景继续给我建议",
                "这里是一段较长的 RAG 证据，解释为什么要优先裁掉旧 recent messages，而不是直接丢掉 facts 和当前问题。"
        );

        assertThat(bundle.contextStrategy()).isEqualTo("recent-summary-facts-budgeted");
        assertThat(bundle.recentMessages()).hasSizeLessThan(4);
        assertThat(bundle.recentMessages()).extracting(ChatMessageRecord::content)
                .doesNotContain("第一轮特别长的问题，主要围绕第一阶段方案和后续演进路线展开。");
        assertThat(bundle.modelMessages()).anyMatch(message -> message.startsWith("EVIDENCE: "));
        assertThat(bundle.modelMessages().getLast()).contains("请结合当前背景继续给我建议");
    }

    private ChatMessageRecord message(String role, String content) {
        return new ChatMessageRecord(role, content, OffsetDateTime.now());
    }
}
