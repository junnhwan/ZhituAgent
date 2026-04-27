package com.zhituagent.api.dto;

import java.time.OffsetDateTime;

public record SessionResponse(
        String sessionId,
        String userId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
