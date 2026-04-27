package com.zhituagent.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record ChatRequest(
        @NotBlank String sessionId,
        @NotBlank String userId,
        @NotBlank String message,
        Map<String, Object> metadata
) {
}
