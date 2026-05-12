package com.zhituagent.session;

import com.zhituagent.api.dto.SessionResponse;

import java.time.OffsetDateTime;

public class SessionMetadata {

    private final String sessionId;
    private final String userId;
    private String title;
    private final OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String tenantId;

    public SessionMetadata(String sessionId, String userId, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public SessionResponse toResponse() {
        return new SessionResponse(sessionId, userId, title, createdAt, updatedAt);
    }
}
