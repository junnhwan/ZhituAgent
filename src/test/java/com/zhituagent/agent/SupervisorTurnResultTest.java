package com.zhituagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorTurnResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldParseStrictJson() {
        String raw = "{\"next\":\"AlertTriageAgent\",\"reason\":\"start with triage\"}";

        SupervisorTurnResult result = SupervisorTurnResult.parseOrFallback(raw, "ReportAgent", mapper);

        assertThat(result.next()).isEqualTo("AlertTriageAgent");
        assertThat(result.reason()).isEqualTo("start with triage");
    }

    @Test
    void shouldExtractJsonWrappedInMarkdownFence() {
        String raw = "```json\n{\"next\":\"LogQueryAgent\",\"reason\":\"need logs\"}\n```";

        SupervisorTurnResult result = SupervisorTurnResult.parseOrFallback(raw, "ReportAgent", mapper);

        assertThat(result.next()).isEqualTo("LogQueryAgent");
        assertThat(result.reason()).isEqualTo("need logs");
    }

    @Test
    void shouldExtractJsonWithSurroundingProse() {
        String raw = "Sure, my decision is: {\"next\":\"ReportAgent\",\"reason\":\"have evidence\"} — proceed.";

        SupervisorTurnResult result = SupervisorTurnResult.parseOrFallback(raw, "ReportAgent", mapper);

        assertThat(result.next()).isEqualTo("ReportAgent");
        assertThat(result.reason()).isEqualTo("have evidence");
    }

    @Test
    void shouldFallbackOnInvalidJson() {
        String raw = "not json at all";

        SupervisorTurnResult result = SupervisorTurnResult.parseOrFallback(raw, "ReportAgent", mapper);

        assertThat(result.next()).isEqualTo("ReportAgent");
        assertThat(result.reason()).contains("parse error");
    }

    @Test
    void shouldFallbackOnEmptyResponse() {
        SupervisorTurnResult fromEmpty = SupervisorTurnResult.parseOrFallback("", "ReportAgent", mapper);
        SupervisorTurnResult fromNull = SupervisorTurnResult.parseOrFallback(null, "ReportAgent", mapper);

        assertThat(fromEmpty.next()).isEqualTo("ReportAgent");
        assertThat(fromEmpty.reason()).contains("empty");
        assertThat(fromNull.next()).isEqualTo("ReportAgent");
    }

    @Test
    void shouldFallbackOnMissingNextField() {
        String raw = "{\"reason\":\"forgot to pick\"}";

        SupervisorTurnResult result = SupervisorTurnResult.parseOrFallback(raw, "ReportAgent", mapper);

        // The regex requires the JSON object to contain "next" as a key,
        // so a payload with only "reason" hits the unparseable branch and falls back.
        assertThat(result.next()).isEqualTo("ReportAgent");
        assertThat(result.reason()).matches("(?i).*(parse error|missing).*");
    }

    @Test
    void shouldHandleExtraFieldsGracefully() {
        String raw = "{\"next\":\"AlertTriageAgent\",\"reason\":\"start\",\"confidence\":0.9}";

        SupervisorTurnResult result = SupervisorTurnResult.parseOrFallback(raw, "ReportAgent", mapper);

        assertThat(result.next()).isEqualTo("AlertTriageAgent");
        assertThat(result.reason()).isEqualTo("start");
    }

    @Test
    void shouldRejectBlankNextOnConstruction() {
        assertThatThrownBy(() -> new SupervisorTurnResult(" ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
