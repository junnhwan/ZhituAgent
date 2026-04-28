package com.zhituagent.memory;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class RedisMemoryLock implements MemoryLock {

    private static final String KEY_PREFIX = "zhitu:memory-lock:";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """,
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    public RedisMemoryLock(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public String tryAcquire(String sessionId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(key(sessionId), token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    @Override
    public void release(String sessionId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(key(sessionId)), token);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
