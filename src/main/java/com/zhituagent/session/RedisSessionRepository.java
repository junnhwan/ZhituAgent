package com.zhituagent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

public class RedisSessionRepository implements SessionRepository {

    private static final String KEY_PREFIX = "zhitu:session:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSessionRepository(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SessionMetadata> findById(String sessionId) {
        String payload = stringRedisTemplate.opsForValue().get(key(sessionId));
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, SessionMetadata.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize session metadata", exception);
        }
    }

    @Override
    public void save(SessionMetadata metadata) {
        try {
            stringRedisTemplate.opsForValue().set(key(metadata.getSessionId()), objectMapper.writeValueAsString(metadata));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize session metadata", exception);
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
