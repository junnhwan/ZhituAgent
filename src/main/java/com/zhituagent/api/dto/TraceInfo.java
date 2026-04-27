package com.zhituagent.api.dto;

public record TraceInfo(
        String path,
        boolean retrievalHit,
        boolean toolUsed
) {
}
