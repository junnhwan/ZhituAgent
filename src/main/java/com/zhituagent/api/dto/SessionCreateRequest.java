package com.zhituagent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SessionCreateRequest(
        @NotBlank String userId,
        String title
) {
}
