package com.zhituagent.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void shouldSetAndGetTenantId() {
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());

        TenantContext.set("tenant-1", "user-1");

        assertEquals("tenant-1", TenantContext.getTenantId());
        assertEquals("user-1", TenantContext.getUserId());
    }

    @Test
    void shouldClearContext() {
        TenantContext.set("tenant-1", "user-1");
        TenantContext.clear();

        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());
    }

    @Test
    void shouldIsolateBetweenThreads() throws InterruptedException {
        TenantContext.set("tenant-main", "user-main");

        Thread otherThread = new Thread(() -> {
            assertNull(TenantContext.getTenantId());
            TenantContext.set("tenant-other", "user-other");
            assertEquals("tenant-other", TenantContext.getTenantId());
        });

        otherThread.start();
        otherThread.join();

        // Main thread should still have original values
        assertEquals("tenant-main", TenantContext.getTenantId());
    }
}
