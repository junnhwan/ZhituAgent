package com.zhituagent.memory;

import java.util.List;

final class SummaryMarkdownValidator {

    static final List<String> REQUIRED_HEADINGS = List.of(
            "### 用户稳定背景",
            "### 已确认目标",
            "### 已做决策",
            "### 重要上下文",
            "### 待跟进问题"
    );

    private SummaryMarkdownValidator() {
    }

    static boolean isValid(String markdown, int maxOutputChars) {
        if (markdown == null || markdown.isBlank()) {
            return false;
        }
        String trimmed = markdown.trim();
        if (maxOutputChars > 0 && trimmed.length() > maxOutputChars) {
            return false;
        }
        return REQUIRED_HEADINGS.stream().allMatch(trimmed::contains);
    }
}
