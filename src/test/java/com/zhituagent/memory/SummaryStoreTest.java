package com.zhituagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummaryStoreTest {

    @Test
    void inMemorySummaryStoreShouldSaveAndReturnStateBySession() {
        InMemorySummaryStore store = new InMemorySummaryStore();
        ConversationSummaryState state = state();

        store.save("sess_store", state);

        Optional<ConversationSummaryState> loaded = store.get("sess_store");
        assertThat(loaded).contains(state);
    }

    @Test
    void redisSummaryStoreShouldSerializeAndDeserializeState() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RedisSummaryStore store = new RedisSummaryStore(redis, objectMapper);
        ConversationSummaryState state = state();

        when(redis.opsForValue()).thenReturn(ops);
        store.save("sess_redis", state);

        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq("zhitu:memory-summary:sess_redis"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("+08:00");

        when(ops.get(anyString())).thenReturn(payload);
        Optional<ConversationSummaryState> loaded = store.get("sess_redis");

        assertThat(loaded).contains(state);
    }

    private ConversationSummaryState state() {
        return new ConversationSummaryState(
                "### 用户稳定背景\n- 用户在补强记忆\n\n### 已确认目标\n- 暂无\n\n### 已做决策\n- 暂无\n\n### 重要上下文\n- 暂无\n\n### 待跟进问题\n- 暂无",
                8,
                OffsetDateTime.parse("2026-05-10T12:00:00+08:00"),
                "gpt-5.4-mini",
                300,
                80
        );
    }
}
