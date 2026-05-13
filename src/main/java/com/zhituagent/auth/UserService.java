package com.zhituagent.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserService {

    private static final String REDIS_KEY_PREFIX = "zhitu:user:";

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringRedisTemplate redisTemplate;
    // Fallback in-memory store when Redis is unavailable
    private final Map<String, User> memoryFallback = new ConcurrentHashMap<>();

    public UserService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public User create(String tenantId, String email, String password) {
        String key = redisKey(tenantId, email);
        User user = new User(tenantId, email.toLowerCase(), passwordEncoder.encode(password));

        try {
            String json = objectMapper.writeValueAsString(user);
            redisTemplate.opsForValue().set(key, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize user", e);
        }

        memoryFallback.put(key, user);
        return user;
    }

    public Optional<User> findByEmail(String tenantId, String email) {
        String key = redisKey(tenantId, email);

        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            try {
                User user = objectMapper.readValue(json, User.class);
                return Optional.of(user);
            } catch (JsonProcessingException e) {
                return Optional.empty();
            }
        }

        // Fallback to memory if Redis missed
        return Optional.ofNullable(memoryFallback.get(key));
    }

    public Optional<User> authenticate(String tenantId, String email, String password) {
        return findByEmail(tenantId, email)
                .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()));
    }

    private static String redisKey(String tenantId, String email) {
        return REDIS_KEY_PREFIX + tenantId + ":" + email.toLowerCase();
    }
}
