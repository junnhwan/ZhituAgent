package com.zhituagent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

public class RedisMemoryStore implements MemoryStore {

    private static final String KEY_PREFIX = "zhitu:memory:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisMemoryStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(String sessionId, ChatMessageRecord message) {
        try {
            stringRedisTemplate.opsForList().rightPush(key(sessionId), objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize chat message", exception);
        }
    }

    @Override
    public List<ChatMessageRecord> list(String sessionId) {
        List<String> payloads = stringRedisTemplate.opsForList().range(key(sessionId), 0, -1);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        return payloads.stream()
                .map(this::readMessage)
                .toList();
    }

    private ChatMessageRecord readMessage(String payload) {
        try {
            return objectMapper.readValue(payload, ChatMessageRecord.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize chat message", exception);
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
