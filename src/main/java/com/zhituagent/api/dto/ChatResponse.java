package com.zhituagent.api.dto;

public record ChatResponse(
        String sessionId,
        String answer,
        TraceInfo trace
) {
}
