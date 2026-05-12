package com.zhituagent.rag;

import com.zhituagent.auth.TenantContext;

import java.util.List;

/**
 * Decorator that injects the current tenant context into all KnowledgeStore
 * operations. Registered as a bean in {@link com.zhituagent.config.InfrastructureConfig}.
 */
public class TenantAwareKnowledgeStore implements KnowledgeStore {

    private final KnowledgeStore delegate;

    public TenantAwareKnowledgeStore(KnowledgeStore delegate) {
        this.delegate = delegate;
    }

    public KnowledgeStore delegate() {
        return delegate;
    }

    @Override
    public void addAll(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            delegate.addAll(chunks);
            return;
        }
        String tenantId = TenantContext.getTenantId();
        // Stamp each chunk with the current tenant before delegating
        List<KnowledgeChunk> stamped = chunks.stream()
                .map(c -> new KnowledgeChunk(
                        c.source(), c.chunkId(), c.content(), c.embedText(), tenantId))
                .toList();
        delegate.addAll(stamped);
    }

    @Override
    public List<KnowledgeSnippet> search(String query, int limit) {
        return delegate.searchByTenant(query, TenantContext.getTenantId(), limit);
    }

    @Override
    public List<KnowledgeSnippet> lexicalSearch(String query, int limit) {
        return delegate.lexicalSearchByTenant(query, TenantContext.getTenantId(), limit);
    }

    @Override
    public List<KnowledgeSnippet> hybridSearch(String query, int limit) {
        return delegate.hybridSearchByTenant(query, TenantContext.getTenantId(), limit);
    }

    @Override
    public boolean supportsNativeHybrid() {
        return delegate.supportsNativeHybrid();
    }
}
