package com.zhituagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RerankResultCalibratorTest {

    private final RerankResultCalibrator calibrator = new RerankResultCalibrator();

    @Test
    void shouldPreferExplicitEnumerationAnswerWhenQueryAsksForCapabilities() {
        List<RetrievalCandidate> candidates = List.of(
                new RetrievalCandidate(
                        "phase-one-vague",
                        "chunk-1",
                        0.95,
                        "Q: 第一版六项能力先做什么？\nA: 第一版先把整体主链跑起来，先做基础能力，后面再继续细化。",
                        0.95,
                        0.0
                ),
                new RetrievalCandidate(
                        "phase-one-precise",
                        "chunk-2",
                        0.89,
                        "Q: 第一版优先六项能力是什么？\nA: 第一版优先 Context、Memory、RAG、Session、SSE、ToolUse 六项能力。",
                        0.89,
                        0.0
                )
        );

        List<RerankResultCalibrator.CalibratedResult> results = calibrator.calibrate(
                "第一版六项能力先做什么？",
                candidates,
                List.of(
                        new RerankClient.RerankResult(0, 0.999741),
                        new RerankClient.RerankResult(1, 0.998683)
                )
        );

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().index()).isEqualTo(1);
        assertThat(results.getFirst().rawScore()).isEqualTo(0.998683);
        assertThat(results.getFirst().calibratedScore()).isGreaterThan(results.get(1).calibratedScore());
    }

    @Test
    void shouldNotOverrideLargeRerankGapOnlyBecauseAnswerLooksStructured() {
        List<RetrievalCandidate> candidates = List.of(
                new RetrievalCandidate(
                        "strong-first",
                        "chunk-1",
                        0.97,
                        "Q: 第一版六项能力先做什么？\nA: 第一版先做 Context、Memory、RAG、Session、SSE、ToolUse。",
                        0.97,
                        0.0
                ),
                new RetrievalCandidate(
                        "far-behind",
                        "chunk-2",
                        0.82,
                        "Q: 第一版优先能力是什么？\nA: 第一版优先 Context、Memory、RAG、Session、SSE、ToolUse 六项能力。",
                        0.82,
                        0.0
                )
        );

        List<RerankResultCalibrator.CalibratedResult> results = calibrator.calibrate(
                "第一版六项能力先做什么？",
                candidates,
                List.of(
                        new RerankClient.RerankResult(0, 0.999741),
                        new RerankClient.RerankResult(1, 0.95)
                )
        );

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().index()).isEqualTo(0);
    }
}
