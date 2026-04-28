package com.zhituagent.memory;

import com.zhituagent.metrics.MemoryMetricsRecorder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {

    @Test
    void shouldCompressOlderMessagesIntoSummaryAndKeepRecentMessages() {
        MemoryService memoryService = new MemoryService(new MessageSummaryCompressor(4, 6));

        memoryService.append("sess_1", "user", "第一轮问题");
        memoryService.append("sess_1", "assistant", "第一轮回答");
        memoryService.append("sess_1", "user", "第二轮问题");
        memoryService.append("sess_1", "assistant", "第二轮回答");
        memoryService.append("sess_1", "user", "第三轮问题");
        memoryService.append("sess_1", "assistant", "第三轮回答");

        MemorySnapshot snapshot = memoryService.snapshot("sess_1");

        assertThat(snapshot.summary()).contains("Earlier conversation summary");
        assertThat(snapshot.recentMessages()).hasSize(4);
        assertThat(snapshot.recentMessages().getFirst().content()).isEqualTo("第二轮问题");
        assertThat(snapshot.recentMessages().getLast().content()).isEqualTo("第三轮回答");
    }

    @Test
    void shouldSkipCompressionWhenLockIsUnavailableButKeepRecentMessages() {
        MemoryService memoryService = new MemoryService(
                new InMemoryMemoryStore(),
                new MessageSummaryCompressor(4, 6),
                (sessionId, ttl) -> null,
                MemoryMetricsRecorder.noop()
        );

        memoryService.append("sess_2", "user", "第一轮问题");
        memoryService.append("sess_2", "assistant", "第一轮回答");
        memoryService.append("sess_2", "user", "第二轮问题");
        memoryService.append("sess_2", "assistant", "第二轮回答");
        memoryService.append("sess_2", "user", "第三轮问题");
        memoryService.append("sess_2", "assistant", "第三轮回答");

        MemorySnapshot snapshot = memoryService.snapshot("sess_2");

        assertThat(snapshot.summary()).isBlank();
        assertThat(snapshot.recentMessages()).hasSize(4);
        assertThat(snapshot.recentMessages().getFirst().content()).isEqualTo("第二轮问题");
        assertThat(snapshot.recentMessages().getLast().content()).isEqualTo("第三轮回答");
    }

    @Test
    void shouldExtractStableFactsFromUserMessagesIntoSnapshot() {
        MemoryService memoryService = new MemoryService(new MessageSummaryCompressor(4, 6));

        memoryService.append("sess_3", "user", "我叫小智");
        memoryService.append("sess_3", "assistant", "你好，小智");
        memoryService.append("sess_3", "user", "我在杭州做 Java Agent 后端开发");

        MemorySnapshot snapshot = memoryService.snapshot("sess_3");

        assertThat(snapshot.facts()).containsExactly(
                "我叫小智",
                "我在杭州做 Java Agent 后端开发"
        );
    }
}
