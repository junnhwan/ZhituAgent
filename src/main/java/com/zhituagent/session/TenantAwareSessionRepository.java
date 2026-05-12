package com.zhituagent.session;

import com.zhituagent.auth.TenantContext;

import java.util.Objects;
import java.util.Optional;

/**
 * Decorator that adds tenant-scoped access control to any {@link SessionRepository}.
 * On save, stamps the current tenant ID from {@link TenantContext}.
 * On findById, filters out sessions belonging to other tenants.
 *
 * Registered as a bean in {@link com.zhituagent.config.InfrastructureConfig}.
 */
public class TenantAwareSessionRepository implements SessionRepository {

    private final SessionRepository delegate;

    public TenantAwareSessionRepository(SessionRepository delegate) {
        this.delegate = delegate;
    }

    public SessionRepository delegate() {
        return delegate;
    }

    @Override
    public Optional<SessionMetadata> findById(String sessionId) {
        String tenantId = TenantContext.getTenantId();
        return delegate.findById(sessionId)
                .filter(s -> Objects.equals(s.getTenantId(), tenantId));
    }

    @Override
    public void save(SessionMetadata metadata) {
        metadata.setTenantId(TenantContext.getTenantId());
        delegate.save(metadata);
    }
}
