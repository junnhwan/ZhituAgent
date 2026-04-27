package com.zhituagent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeWriteRequest(
        @NotBlank String question,
        @NotBlank String answer,
        @NotBlank String sourceName
) {
}
