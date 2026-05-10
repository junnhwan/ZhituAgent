package com.zhituagent.memory;

public record SummaryResult(
        SummaryOutcome outcome,
        String summaryMarkdown,
        String modelName,
        long latencyMs,
        long inputTokenEstimate,
        long outputTokenEstimate,
        boolean truncated,
        String errorMessage
) {

    public SummaryResult {
        outcome = outcome == null ? SummaryOutcome.ERROR : outcome;
        summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown;
        modelName = modelName == null || modelName.isBlank() ? "unknown" : modelName;
        latencyMs = Math.max(0, latencyMs);
        inputTokenEstimate = Math.max(0, inputTokenEstimate);
        outputTokenEstimate = Math.max(0, outputTokenEstimate);
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static SummaryResult success(String summaryMarkdown,
                                        String modelName,
                                        long latencyMs,
                                        long inputTokenEstimate,
                                        long outputTokenEstimate,
                                        boolean truncated) {
        return new SummaryResult(
                SummaryOutcome.SUCCESS,
                summaryMarkdown,
                modelName,
                latencyMs,
                inputTokenEstimate,
                outputTokenEstimate,
                truncated,
                ""
        );
    }

    public static SummaryResult timeout(String modelName, long latencyMs, long inputTokenEstimate) {
        return new SummaryResult(
                SummaryOutcome.TIMEOUT,
                "",
                modelName,
                latencyMs,
                inputTokenEstimate,
                0,
                false,
                "summary timed out"
        );
    }

    public static SummaryResult error(String modelName,
                                      long latencyMs,
                                      long inputTokenEstimate,
                                      long outputTokenEstimate,
                                      String errorMessage) {
        return new SummaryResult(
                SummaryOutcome.ERROR,
                "",
                modelName,
                latencyMs,
                inputTokenEstimate,
                outputTokenEstimate,
                false,
                errorMessage
        );
    }

    public static SummaryResult disabled() {
        return new SummaryResult(SummaryOutcome.DISABLED, "", "disabled", 0, 0, 0, false, "");
    }
}
