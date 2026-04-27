package com.zhituagent.tool.builtin;

import com.zhituagent.api.dto.SessionDetailResponse;
import com.zhituagent.session.SessionService;
import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SessionInspectTool implements ToolDefinition {

    private final SessionService sessionService;

    public SessionInspectTool(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public String name() {
        return "session-inspect";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String sessionId = String.valueOf(arguments.getOrDefault("sessionId", ""));
        SessionDetailResponse detail = sessionService.getSession(sessionId);
        return new ToolResult(
                name(),
                true,
                "session loaded: " + sessionId,
                Map.of(
                        "summary", detail.summary(),
                        "recentMessages", detail.recentMessages().size()
                )
        );
    }
}
