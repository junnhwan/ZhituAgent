package com.zhituagent.memory;

import java.time.Duration;

@FunctionalInterface
public interface MemoryLock {

    String tryAcquire(String sessionId, Duration ttl);

    default void release(String sessionId, String token) {
        // no-op
    }
}
