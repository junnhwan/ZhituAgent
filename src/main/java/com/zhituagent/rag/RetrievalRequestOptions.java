package com.zhituagent.rag;

import java.util.Set;

public record RetrievalRequestOptions(
        RetrievalMode mode,
        Set<String> sourceAllowlist
) {

    public RetrievalRequestOptions {
        mode = mode == null ? RetrievalMode.DEFAULT : mode;
        sourceAllowlist = sourceAllowlist == null ? null : Set.copyOf(sourceAllowlist);
    }

    public static RetrievalRequestOptions defaults() {
        return new RetrievalRequestOptions(RetrievalMode.DEFAULT, null);
    }

    public static RetrievalRequestOptions withMode(RetrievalMode mode) {
        return new RetrievalRequestOptions(mode, null);
    }

    public static RetrievalRequestOptions scoped(RetrievalMode mode, Set<String> sourceAllowlist) {
        return new RetrievalRequestOptions(mode, sourceAllowlist);
    }

    public boolean hasSourceAllowlist() {
        return sourceAllowlist != null;
    }
}
