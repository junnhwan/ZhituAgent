package com.zhituagent.api.dto;

import java.util.List;

public record SessionDetailResponse(
        SessionResponse session,
        String summary,
        List<ChatMessageView> recentMessages,
        List<String> facts
) {
}
