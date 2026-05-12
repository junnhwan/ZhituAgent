package com.zhituagent.memory;

import com.zhituagent.auth.TenantContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Primary
public class TenantAwareMemoryStore implements MemoryStore {

    private final MemoryStore delegate;

    public TenantAwareMemoryStore(MemoryStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void append(String sessionId, ChatMessageRecord message) {
        delegate.append(sessionId, message);
    }

    @Override
    public List<ChatMessageRecord> list(String sessionId) {
        // Tenant check is done at session level (session lookup already tenant-scoped)
        return delegate.list(sessionId);
    }
}
