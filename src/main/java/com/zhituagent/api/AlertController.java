package com.zhituagent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.agent.MultiAgentOrchestrator;
import com.zhituagent.agent.MultiAgentResult;
import com.zhituagent.agent.MultiAgentStageEvent;
import com.zhituagent.api.sse.SseEventType;
import com.zhituagent.config.AppProperties;
import com.zhituagent.trace.SpanCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SRE alert analysis entry point. Only registered when
 * {@code zhitu.app.multi-agent-enabled=true}; with the flag off this controller
 * is absent and {@code POST /api/alert} returns 404 — preserving the v2 surface.
 */
@RestController
@RequestMapping("/api/alert")
@ConditionalOnProperty(prefix = "zhitu.app", name = "multi-agent-enabled", havingValue = "true")
public class AlertController {

    private static final Logger LOG = LoggerFactory.getLogger(AlertController.class);

    private final MultiAgentOrchestrator orchestrator;
    private final SpanCollector spanCollector;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public AlertController(MultiAgentOrchestrator orchestrator,
                           SpanCollector spanCollector,
                           AppProperties appProperties,
                           ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.spanCollector = spanCollector;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        LOG.info("AlertController registered (multi-agent-enabled=true, maxRounds={})",
                appProperties.getMultiAgentMaxRounds());
    }

    @PostMapping
    public Map<String, Object> analyzeAlert(@RequestBody JsonNode alert) {
        String alertJson = alert == null ? "{}" : alert.toPrettyString();
        String sessionId = "alert-" + UUID.randomUUID();
        long start = System.currentTimeMillis();

        String traceId = spanCollector.beginTrace();
        String rootSpan = spanCollector.startSpan("chat.turn", "request",
                Map.of("sessionId", sessionId, "type", "alert"));
        MultiAgentResult result = null;
        try {
            result = orchestrator.run(
                    alertJson,
                    Map.of("sessionId", sessionId, "trigger", "alert"),
                    appProperties.getMultiAgentMaxRounds()
            );
        } finally {
            spanCollector.endSpan(rootSpan, "ok",
                    Map.of("agentCount", result == null ? 0 : result.agentTrail().size()));
            spanCollector.drain();
        }

        long latencyMs = System.currentTimeMillis() - start;
        LOG.info("alert-controller.completed sessionId={} agentTrail={} latencyMs={}",
                sessionId, result.agentTrail(), latencyMs);

        return buildReportPayload(traceId, sessionId, result, latencyMs);
    }

    /**
     * Streaming variant. Reuses the multi-agent stage callback to emit live
     * supervisor / specialist phase events, then a final {@code report} event
     * carrying the same payload as the synchronous endpoint.
     *
     * <p>Wire format:
     * <pre>
     *   start  → {sessionId, traceId}
     *   stage  → {phase, agentName?, round?}                  (one per supervisor turn / specialist turn)
     *   report → {reportMarkdown, agentTrail, supervisorDecisions, executions, rounds, reachedMaxRounds, latencyMs}
     *   complete → {}
     * </pre>
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAlert(@RequestBody JsonNode alert) {
        String alertJson = alert == null ? "{}" : alert.toPrettyString();
        String sessionId = "alert-" + UUID.randomUUID();
        long start = System.currentTimeMillis();

        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            String traceId = spanCollector.beginTrace();
            String rootSpan = spanCollector.startSpan("chat.turn", "request",
                    Map.of("sessionId", sessionId, "type", "alert.stream"));
            MultiAgentResult result = null;
            try {
                emitter.send(SseEmitter.event()
                        .name(SseEventType.START.value())
                        .data(writeJson(Map.of("sessionId", sessionId, "traceId", traceId))));

                result = orchestrator.run(
                        alertJson,
                        Map.of("sessionId", sessionId, "trigger", "alert.stream"),
                        appProperties.getMultiAgentMaxRounds(),
                        stageEvent -> sendStage(emitter, stageEvent)
                );

                long latencyMs = System.currentTimeMillis() - start;
                Map<String, Object> reportPayload = buildReportPayload(traceId, sessionId, result, latencyMs);
                emitter.send(SseEmitter.event()
                        .name(SseEventType.REPORT.value())
                        .data(writeJson(reportPayload)));
                emitter.send(SseEmitter.event()
                        .name(SseEventType.COMPLETE.value())
                        .data(writeJson(Map.of())));
                emitter.complete();
                LOG.info("alert-controller.stream-completed sessionId={} agentTrail={} latencyMs={}",
                        sessionId, result.agentTrail(), latencyMs);
            } catch (Exception e) {
                LOG.warn("alert-controller.stream-failed sessionId={} error={}", sessionId, e.toString());
                try {
                    emitter.send(SseEmitter.event()
                            .name(SseEventType.ERROR.value())
                            .data(writeJson(Map.of("code", "alert.stream.failed", "message", e.getMessage() == null ? "" : e.getMessage()))));
                } catch (IOException ignored) {
                    // emitter already broken
                }
                emitter.completeWithError(e);
            } finally {
                spanCollector.endSpan(rootSpan, result == null ? "error" : "ok",
                        Map.of("agentCount", result == null ? 0 : result.agentTrail().size()));
                spanCollector.drain();
            }
        });
        return emitter;
    }

    private void sendStage(SseEmitter emitter, MultiAgentStageEvent event) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            if (event.agentName() != null) {
                detail.put("agentName", event.agentName());
            }
            detail.put("round", event.round());
            emitter.send(SseEmitter.event()
                    .name(SseEventType.STAGE.value())
                    .data(writeJson(Map.of("phase", event.phase(), "detail", detail))));
        } catch (IOException e) {
            throw new IllegalStateException("alert.stream.stage-emit-failed", e);
        }
    }

    private Map<String, Object> buildReportPayload(String traceId, String sessionId,
                                                   MultiAgentResult result, long latencyMs) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("traceId", traceId);
        response.put("sessionId", sessionId);
        response.put("reportMarkdown", result.finalAnswer());
        response.put("agentTrail", result.agentTrail());
        response.put("supervisorDecisions", result.supervisorDecisions().stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("round", d.round());
                    m.put("next", d.next());
                    m.put("reason", d.reason());
                    m.put("latencyMs", d.latencyMs());
                    return m;
                })
                .toList());
        response.put("executions", result.executions().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("agentName", e.agentName());
                    m.put("iterations", e.iterations());
                    m.put("toolsUsed", e.toolsUsed());
                    m.put("latencyMs", e.latencyMs());
                    return m;
                })
                .toList());
        response.put("rounds", result.rounds());
        response.put("reachedMaxRounds", result.reachedMaxRounds());
        response.put("latencyMs", latencyMs);
        return response;
    }

    private String writeJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}
