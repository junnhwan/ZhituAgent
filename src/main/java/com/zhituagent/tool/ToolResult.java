package com.zhituagent.tool;

import java.util.Map;

public record ToolResult(
        String toolName,
        boolean success,
        String summary,
        Map<String, Object> payload
) {
}
