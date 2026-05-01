package com.zhituagent.api.sse;

/**
 * Canonical SSE event names used by the chat streaming endpoint.
 *
 * <p>Until T1 these were string literals scattered through ChatController
 * ({@code start / token / complete / error}). Centralizing them as an enum lets
 * the trace-tree work add new events ({@code span_start / span_end / tool_*})
 * with a single lookup table and lets the frontend's discriminated-union types
 * stay aligned with backend reality.
 */
public enum SseEventType {

    START("start"),
    TOKEN("token"),
    COMPLETE("complete"),
    ERROR("error"),
    STAGE("stage"),

    SPAN_START("span_start"),
    SPAN_END("span_end"),
    RETRIEVAL_STEP("retrieval_step"),
    TOOL_START("tool_start"),
    TOOL_END("tool_end"),
    THINKING_DELTA("thinking_delta"),
    TOOL_CALL_PENDING("tool_call_pending"),
    TOOL_CALL_RESOLVED("tool_call_resolved");

    private final String value;

    SseEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
