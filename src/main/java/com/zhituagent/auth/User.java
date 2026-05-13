package com.zhituagent.auth;

import java.util.UUID;

public class User {
    private String id;
    private String tenantId;
    private String email;
    private String passwordHash;
    private long createdAt;

    public User() {}

    public User(String tenantId, String email, String passwordHash) {
        this.id = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
