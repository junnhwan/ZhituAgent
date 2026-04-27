package com.zhituagent.tool;

import java.util.Map;

public interface ToolDefinition {

    String name();

    ToolResult execute(Map<String, Object> arguments);
}
