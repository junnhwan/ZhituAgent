package com.zhituagent.auth;

public class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    public static void set(String tenantId, String userId) {
        TENANT_ID.set(tenantId);
        USER_ID.set(userId);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
    }
}
