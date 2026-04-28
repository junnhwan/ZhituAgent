package com.zhituagent.rag;

import java.util.Arrays;

public enum RetrievalMode {

    DEFAULT("default", null, null),
    DENSE("dense", false, false),
    DENSE_RERANK("dense-rerank", false, true),
    HYBRID("hybrid", true, false),
    HYBRID_RERANK("hybrid-rerank", true, true);

    private final String value;
    private final Boolean hybridEnabled;
    private final Boolean rerankEnabled;

    RetrievalMode(String value, Boolean hybridEnabled, Boolean rerankEnabled) {
        this.value = value;
        this.hybridEnabled = hybridEnabled;
        this.rerankEnabled = rerankEnabled;
    }

    public String value() {
        return value;
    }

    public boolean resolveHybrid(boolean defaultValue) {
        return hybridEnabled == null ? defaultValue : hybridEnabled;
    }

    public boolean resolveRerank(boolean defaultValue) {
        return rerankEnabled == null ? defaultValue : rerankEnabled;
    }

    public static RetrievalMode fromValue(String value) {
        if (value == null || value.isBlank() || DEFAULT.value.equalsIgnoreCase(value)) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(mode -> mode.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported retrieval mode: " + value));
    }
}
