package com.zhituagent.api.dto;

public record SessionCreateRequest(
        String userId,
        String title
) {
}
