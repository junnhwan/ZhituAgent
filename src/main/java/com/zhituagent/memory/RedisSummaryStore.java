package com.zhituagent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

public class RedisSummaryStore implements SummaryStore {

    private static final String KEY_PREFIX = "zhitu:memory-summary:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSummaryStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper.copy()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }

    @Override
    public Optional<ConversationSummaryState> get(String sessionId) {
        String payload = stringRedisTemplate.opsForValue().get(key(sessionId));
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, ConversationSummaryState.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize conversation summary state", exception);
        }
    }

    @Override
    public void save(String sessionId, ConversationSummaryState state) {
        try {
            stringRedisTemplate.opsForValue().set(key(sessionId), objectMapper.writeValueAsString(state));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize conversation summary state", exception);
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
