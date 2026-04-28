package com.zhituagent.memory;

import java.time.Duration;

public class NoopMemoryLock implements MemoryLock {

    @Override
    public String tryAcquire(String sessionId, Duration ttl) {
        return "noop-lock";
    }
}
