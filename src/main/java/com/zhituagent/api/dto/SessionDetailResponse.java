package com.zhituagent.api.dto;

import java.util.List;

public record SessionDetailResponse(
        SessionResponse session,
        String summary,
        List<ChatMessageView> messages,
        List<String> facts
) {
}
