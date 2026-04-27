package com.zhituagent.api.dto;

import java.time.OffsetDateTime;

public record ChatMessageView(
        String role,
        String content,
        OffsetDateTime timestamp
) {
}
