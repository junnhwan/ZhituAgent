package com.zhituagent.context;

import com.zhituagent.memory.ChatMessageRecord;
import com.zhituagent.memory.MemoryService;
import com.zhituagent.memory.MemorySnapshot;
import com.zhituagent.memory.MessageSummaryCompressor;
import com.zhituagent.memory.SummarySource;
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


    @Test
    void shouldKeepMinimumRecentMessagesEvenUnderTightBudget() {
        // Tight budget — system prompt + 1 fact alone trips Tier 1, but keepRecentN=2
        // floor must prevent the trimmer from clearing recent history entirely.
        ContextManager contextManager = new ContextManager(120, 30, 30, 30, 30);

        ContextBundle bundle = contextManager.build(
                "你是测试助手",
                new com.zhituagent.memory.MemorySnapshot(
                        "比较长的会话总结,讨论上下文管理与裁剪策略。",
                        List.of(
                                message("user", "第一轮特别长的问题,讨论方案 A。"),
                                message("assistant", "第一轮特别长的回答,解释 A 的取舍。"),
                                message("user", "第二轮长问题,继续追问预算控制。"),
                                message("assistant", "第二轮长回答,说明 recent/summary 优先级。"),
                                message("user", "第三轮短问题。"),
                                message("assistant", "第三轮短回答。")
                        ),
                        List.of("我叫小智")
                ),
                "请结合当前背景继续给建议",
                "RAG 证据段。"
        );

        assertThat(bundle.recentMessages()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(bundle.contextStrategy()).contains("-budgeted");
        // Strategy may carry -overflow if the floor is hit and budget still over;
        // either way the floor itself must not be violated.
    }

    @Test
    void shouldStampOverflowWhenBudgetCannotBeMetEvenAfterAllTiers() {
        // Absurdly small budget — even the irreducible floor (system prompt +
        // minKeepRecent + halved evidence + current msg) cannot fit.
        ContextManager contextManager = new ContextManager(40, 10, 10, 10, 10);

        ContextBundle bundle = contextManager.build(
                "你是一个非常长的系统提示符,这段话本身就远超 40 token 预算,任何动态部分加进来都不可能在 40 token 内塞下。",
                new com.zhituagent.memory.MemorySnapshot(
                        "总结",
                        List.of(
                                message("user", "短问题"),
                                message("assistant", "短回答")
                        ),
                        List.of("我叫小智")
                ),
                "当前问题",
                "证据"
        );

        assertThat(bundle.contextStrategy()).endsWith("-overflow");
        assertThat(bundle.recentMessages()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldTrimRecentMessageContentBeforeStampingOverflow() {
        ContextManager contextManager = new ContextManager(360, 20, 20, 20, 80, 2);

        ContextBundle bundle = contextManager.build(
                "系统提示 ".repeat(40),
                new com.zhituagent.memory.MemorySnapshot(
                        "旧摘要会被丢弃",
                        List.of(
                                message("user", "第一条旧消息会先被移除。"),
                                message("assistant", "第二条旧消息也会先被移除。"),
                                message("user", "这是一条必须保留但可以继续压缩内容的最近用户消息。".repeat(8)),
                                message("assistant", "这是一条必须保留但可以继续压缩内容的最近助手消息。".repeat(8))
                        ),
                        List.of("我在杭州做 Java Agent 后端开发")
                ),
                "当前问题 ".repeat(30),
                ""
        );

        assertThat(bundle.contextStrategy()).isEqualTo("recent-summary-facts-budgeted");
        assertThat(bundle.recentMessages()).hasSize(2);
        assertThat(bundle.recentMessages()).extracting(ChatMessageRecord::content)
                .allMatch(content -> content.endsWith("..."));
        assertThat(new TokenEstimator().estimateMessages(bundle.modelMessages())).isLessThanOrEqualTo(360);
    }

    @Test
    void shouldPreserveMarkdownSummaryAndStampLlmSummaryStrategy() {
        ContextManager contextManager = new ContextManager();
        String markdownSummary = """
                ### 用户稳定背景
                - 用户在补强会话记忆

                ### 已确认目标
                - 使用 mini 模型做增量摘要

                ### 已做决策
                - 默认关闭，显式启用

                ### 重要上下文
                - 摘要格式必须可读

                ### 待跟进问题
                - 暂无
                """;

        ContextBundle bundle = contextManager.build(
                "你是测试助手",
                new MemorySnapshot(markdownSummary, List.of(), List.of(), SummarySource.LLM),
                "继续讨论实现",
                ""
        );

        assertThat(bundle.summary()).contains("### 用户稳定背景");
        assertThat(bundle.modelMessages())
                .anyMatch(message -> message.startsWith("SUMMARY: ### 用户稳定背景")
                        && message.contains("### 已确认目标"));
        assertThat(bundle.contextStrategy()).isEqualTo("recent-summary-llm-summary");
    }

    @Test
    void shouldStampRuleSummaryStrategyWhenFallbackSummaryIsUsed() {
        ContextManager contextManager = new ContextManager();

        ContextBundle bundle = contextManager.build(
                "你是测试助手",
                new MemorySnapshot("### 用户稳定背景\n- 暂无", List.of(), List.of(), SummarySource.RULE),
                "继续讨论实现",
                ""
        );

        assertThat(bundle.contextStrategy()).isEqualTo("recent-summary-rule-summary");
    }

    private ChatMessageRecord message(String role, String content) {
        return new ChatMessageRecord(role, content, OffsetDateTime.now());
    }
}
