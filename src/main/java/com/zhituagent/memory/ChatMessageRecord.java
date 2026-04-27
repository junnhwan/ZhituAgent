package com.zhituagent.memory;

import java.time.OffsetDateTime;

public record ChatMessageRecord(
        String role,
        String content,
        OffsetDateTime timestamp
) {
}
