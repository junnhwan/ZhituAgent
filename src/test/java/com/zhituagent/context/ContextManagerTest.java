package com.zhituagent.context;

import com.zhituagent.memory.MemoryService;
import com.zhituagent.memory.MessageSummaryCompressor;
import org.junit.jupiter.api.Test;

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
}
