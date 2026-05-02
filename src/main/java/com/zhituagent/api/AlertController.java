package com.zhituagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhituagent.agent.MultiAgentOrchestrator;
import com.zhituagent.agent.MultiAgentResult;
import com.zhituagent.config.AppProperties;
import com.zhituagent.trace.SpanCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public AlertController(MultiAgentOrchestrator orchestrator,
                           SpanCollector spanCollector,
                           AppProperties appProperties) {
        this.orchestrator = orchestrator;
        this.spanCollector = spanCollector;
        this.appProperties = appProperties;
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
}
