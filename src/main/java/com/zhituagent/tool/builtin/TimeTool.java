package com.zhituagent.tool.builtin;

import com.zhituagent.tool.ToolDefinition;
import com.zhituagent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class TimeTool implements ToolDefinition {

    private final Clock clock;

    public TimeTool() {
        this(Clock.systemDefaultZone());
    }

    public TimeTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "time";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String now = ZonedDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new ToolResult(
                name(),
                true,
                "current time is " + now,
                Map.of("time", now)
        );
    }
}
