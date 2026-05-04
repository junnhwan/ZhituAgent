package com.zhituagent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.api.dto.ChatRequest;
import com.zhituagent.api.dto.ChatResponse;
import com.zhituagent.api.dto.TraceInfo;
import com.zhituagent.api.sse.SseEventType;
import com.zhituagent.chat.ChatService;
import com.zhituagent.config.AppProperties;
import com.zhituagent.context.ContextBundle;
import com.zhituagent.context.ContextManager;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.metrics.ChatMetricsRecorder;
import com.zhituagent.metrics.ToolMetricsRecorder;
import com.zhituagent.memory.MemoryService;
import com.zhituagent.orchestrator.AgentOrchestrator;
import com.zhituagent.orchestrator.RouteDecision;
import com.zhituagent.session.SessionService;
import com.zhituagent.tool.ToolResult;
import com.zhituagent.trace.TraceArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final LlmRuntime llmRuntime;
    private final SessionService sessionService;
    private final MemoryService memoryService;
    private final ContextManager contextManager;
    private final AgentOrchestrator agentOrchestrator;
    private final ChatTraceFactory chatTraceFactory;
    private final ChatMetricsRecorder chatMetricsRecorder;
    private final ToolMetricsRecorder toolMetricsRecorder;
    private final TraceArchiveService traceArchiveService;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public ChatController(ChatService chatService,
                          LlmRuntime llmRuntime,
                          SessionService sessionService,
                          MemoryService memoryService,
                          ContextManager contextManager,
                          AgentOrchestrator agentOrchestrator,
                          ChatTraceFactory chatTraceFactory,
                          ChatMetricsRecorder chatMetricsRecorder,
                          ToolMetricsRecorder toolMetricsRecorder,
                          TraceArchiveService traceArchiveService,
                          ObjectMapper objectMapper,
                          AppProperties appProperties,
                          ResourceLoader resourceLoader) throws IOException {
        this.chatService = chatService;
        this.llmRuntime = llmRuntime;
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.contextManager = contextManager;
        this.agentOrchestrator = agentOrchestrator;
        this.chatTraceFactory = chatTraceFactory;
        this.chatMetricsRecorder = chatMetricsRecorder;
        this.toolMetricsRecorder = toolMetricsRecorder;
        this.traceArchiveService = traceArchiveService;
        this.objectMapper = objectMapper;
        Resource resource = resourceLoader.getResource(appProperties.getSystemPromptLocation());
        this.systemPrompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping(path = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest servletRequest) {
        String requestId = requestIdOf(servletRequest);
        return chatService.chat(
                request.sessionId(),
                request.userId(),
                request.message(),
                requestId,
                request.metadata()
        );
    }

    @PostMapping(path = "/streamChat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request, HttpServletRequest servletRequest) {
        long startNanos = System.nanoTime();
        String requestId = requestIdOf(servletRequest);
        sessionService.ensureSession(request.sessionId(), request.userId());
        sessionService.appendMessage(request.sessionId(), request.userId(), "user", request.message());

        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            StringBuilder answerBuilder = new StringBuilder();
            RouteDecision routeDecision = null;
            ContextBundle contextBundle = null;
            try {
                emitter.send(SseEmitter.event()
                        .name(SseEventType.START.value())
                        .data(writeJson(Map.of("sessionId", request.sessionId()))));

                emitStage(emitter, "retrieving", Map.of());

                routeDecision = agentOrchestrator.decide(
                        request.message(),
                        com.zhituagent.rag.RetrievalRequestOptions.defaults(),
                        buildToolMetadata(request)
                );
                recordToolMetric(routeDecision);
                logRouteDecision("chat.stream.route.selected", requestId, request.sessionId(), routeDecision);

                if (routeDecision.toolUsed() && routeDecision.toolName() != null) {
                    emitStage(emitter, "calling-tool", Map.of("toolName", routeDecision.toolName()));
                    emitToolLifecycle(emitter, routeDecision);
                }

                contextBundle = contextManager.build(
                        systemPrompt,
                        memoryService.snapshot(request.sessionId()),
                        request.message(),
                        buildEvidenceBlock(routeDecision)
                );

                emitStage(emitter, "generating", Map.of());

                final RouteDecision finalRoute = routeDecision;
                final ContextBundle finalContext = contextBundle;
                llmRuntime.stream(
                        systemPrompt,
                        finalContext.modelMessages(),
                        request.metadata() == null ? Map.of() : request.metadata(),
                        token -> {
                            answerBuilder.append(token);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(SseEventType.TOKEN.value())
                                        .data(writeJson(Map.of("content", token))));
                            } catch (IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                        },
                        () -> {
                            try {
                                sessionService.appendMessage(request.sessionId(), request.userId(), "assistant", answerBuilder.toString());
                                long latencyMs = elapsedMillis(startNanos);
                                TraceInfo traceInfo = chatTraceFactory.create(
                                        finalRoute,
                                        requestId,
                                        latencyMs,
                                        finalContext,
                                        answerBuilder.toString()
                                );
                                traceArchiveService.archiveSuccess(
                                        "chat.stream.completed",
                                        true,
                                        request.sessionId(),
                                        request.userId(),
                                        requestId,
                                        request.message(),
                                        answerBuilder.toString(),
                                        traceInfo,
                                        finalRoute
                                );
                                log.info(
                                        "chat.stream.completed sessionId={} path={} retrievalHit={} toolUsed={} requestId={} answerLength={} latencyMs={}",
                                        request.sessionId(),
                                        finalRoute.path(),
                                        finalRoute.retrievalHit(),
                                        finalRoute.toolUsed(),
                                        requestId,
                                        answerBuilder.length(),
                                        latencyMs
                                );
                                chatMetricsRecorder.recordRequest(finalRoute.path(), true, true, latencyMs);
                                emitPendingApprovalIfNeeded(emitter, finalRoute);
                                emitter.send(SseEmitter.event()
                                        .name(SseEventType.COMPLETE.value())
                                        .data(writeJson(traceInfo)));
                                emitter.complete();
                            } catch (IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                        }
                );
            } catch (Exception exception) {
                long latencyMs = elapsedMillis(startNanos);
                String routePath = routeDecision == null ? "unknown" : routeDecision.path();
                log.error(
                        "chat.stream.failed sessionId={} requestId={} path={} message={}",
                        request.sessionId(),
                        requestId,
                        routePath,
                        exception.getMessage(),
                        exception
                );
                chatMetricsRecorder.recordRequest(routePath, true, false, latencyMs);
                traceArchiveService.archiveFailure(
                        "chat.stream.failed",
                        true,
                        request.sessionId(),
                        request.userId(),
                        requestId,
                        request.message(),
                        answerBuilder.toString(),
                        exception.getMessage(),
                        latencyMs,
                        routeDecision,
                        contextBundle
                );
                try {
                    emitter.send(SseEmitter.event()
                            .name(SseEventType.ERROR.value())
                            .data(writeJson(Map.of("code", "INTERNAL_ERROR", "message", exception.getMessage(), "requestId", requestId))));
                } catch (IOException ignored) {
                    // Ignore secondary streaming errors.
                }
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private void emitStage(SseEmitter emitter, String phase, Map<String, Object> detail) {
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("phase", phase);
            payload.put("detail", detail == null ? Map.of() : detail);
            emitter.send(SseEmitter.event()
                    .name(SseEventType.STAGE.value())
                    .data(writeJson(payload)));
        } catch (IOException ignored) {
            // Stage events are best-effort; failure here should not abort the stream.
        }
    }

    private String writeJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private String buildEvidenceBlock(RouteDecision routeDecision) {
        if (routeDecision == null) {
            return "";
        }
        if (routeDecision.toolUsed() && routeDecision.toolResult() != null) {
            return "TOOL RESULT: " + routeDecision.toolResult().summary();
        }
        if (routeDecision.retrievalHit() && !routeDecision.snippets().isEmpty()) {
            return routeDecision.snippets().stream()
                    .map(snippet -> "[" + snippet.source() + "] " + snippet.content())
                    .reduce((left, right) -> left + "\n---\n" + right)
                    .orElse("");
        }
        return "";
    }

    private void recordToolMetric(RouteDecision routeDecision) {
        if (routeDecision != null && routeDecision.toolUsed()) {
            toolMetricsRecorder.recordInvocation(routeDecision.toolName(), true);
        }
    }

    private void logRouteDecision(String eventName, String requestId, String sessionId, RouteDecision routeDecision) {
        if (routeDecision == null) {
            log.info("路由决策已生成 {} sessionId={} requestId={} path=direct-answer retrievalHit=false toolUsed=false snippetCount=0", eventName, sessionId, requestId);
            return;
        }
        log.info(
                "路由决策已生成 {} sessionId={} requestId={} path={} retrievalHit={} toolUsed={} snippetCount={} topSource={} topScore={}",
                eventName,
                sessionId,
                requestId,
                routeDecision.path(),
                routeDecision.retrievalHit(),
                routeDecision.toolUsed(),
                routeDecision.snippets() == null ? 0 : routeDecision.snippets().size(),
                topSource(routeDecision),
                topScore(routeDecision)
        );
    }

    private String topSource(RouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.snippets() == null || routeDecision.snippets().isEmpty()) {
            return "-";
        }
        return routeDecision.snippets().getFirst().source();
    }

    private String topScore(RouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.snippets() == null || routeDecision.snippets().isEmpty()) {
            return "-";
        }
        return String.format("%.4f", routeDecision.snippets().getFirst().score());
    }

    private String requestIdOf(HttpServletRequest servletRequest) {
        Object requestId = servletRequest.getAttribute("requestId");
        return requestId == null ? "-" : requestId.toString();
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private Map<String, Object> buildToolMetadata(ChatRequest request) {
        Map<String, Object> merged = new java.util.HashMap<>();
        if (request.metadata() != null) {
            merged.putAll(request.metadata());
        }
        merged.put(com.zhituagent.orchestrator.ToolCallExecutor.METADATA_SESSION_ID,
                request.sessionId() == null ? "" : request.sessionId());
        return merged;
    }

    private void emitPendingApprovalIfNeeded(SseEmitter emitter, RouteDecision routeDecision) {
        if (routeDecision == null || !routeDecision.toolUsed() || routeDecision.toolResult() == null) {
            return;
        }
        Map<String, Object> payload = routeDecision.toolResult().payload();
        if (payload == null) {
            return;
        }
        Object status = payload.get("status");
        if (!"AWAITING_APPROVAL".equals(status)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(SseEventType.TOOL_CALL_PENDING.value())
                    .data(writeJson(payload)));
        } catch (IOException ignored) {
            // Pending notification is best-effort; the trace also carries the same payload.
        }
    }

    /**
     * Emit a {@code tool_start} + {@code tool_end} pair for the tool invoked
     * during this request, derived from the {@link RouteDecision}'s captured
     * {@link ToolResult}. The current pipeline runs tools synchronously inside
     * {@code agentOrchestrator.decide()} so by the time we emit, the call has
     * already completed — we surface both lifecycle events back-to-back so the
     * frontend can still render the {@code <ToolCallCard>} 4-state pill and
     * 🔌 server badge for MCP-sourced tools.
     *
     * <p>Real per-tool latency would require {@code ToolCallExecutor} to accept
     * a listener; that refactor is out of scope for this milestone.
     */
    private void emitToolLifecycle(SseEmitter emitter, RouteDecision routeDecision) {
        if (routeDecision == null || !routeDecision.toolUsed() || routeDecision.toolName() == null) {
            return;
        }
        ToolResult result = routeDecision.toolResult();
        Map<String, Object> resultPayload = result == null || result.payload() == null
                ? Map.of()
                : result.payload();
        String source = String.valueOf(resultPayload.getOrDefault("source", "builtin"));
        String mcpServer = String.valueOf(resultPayload.getOrDefault("mcpServer", ""));
        String mcpTransport = String.valueOf(resultPayload.getOrDefault("mcpTransport", ""));
        String toolCallId = "call_" + System.nanoTime();
        String toolName = routeDecision.toolName();

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("toolCallId", toolCallId);
        startPayload.put("name", toolName);
        startPayload.put("source", source);
        if (!mcpServer.isEmpty()) {
            startPayload.put("server", mcpServer);
        }
        if (!mcpTransport.isEmpty()) {
            startPayload.put("transport", mcpTransport);
        }
        // Raw args aren't surfaced through RouteDecision today; surface an empty
        // map so the frontend's "args" section can render a placeholder.
        startPayload.put("args", Map.of());

        try {
            emitter.send(SseEmitter.event()
                    .name(SseEventType.TOOL_START.value())
                    .data(writeJson(startPayload)));
        } catch (IOException ignored) {
            // tool_start is best-effort UX; failure here must not abort the stream.
        }

        Map<String, Object> endPayload = new LinkedHashMap<>();
        endPayload.put("toolCallId", toolCallId);
        endPayload.put("status", result != null && result.success() ? "success" : "error");
        // Synthetic 0ms — single-pass pipeline doesn't track per-tool timing yet.
        endPayload.put("durationMs", 0L);
        String summary = result == null ? "" : (result.summary() == null ? "" : result.summary());
        endPayload.put("resultPreview", summary.length() > 200 ? summary.substring(0, 200) + "..." : summary);

        try {
            emitter.send(SseEmitter.event()
                    .name(SseEventType.TOOL_END.value())
                    .data(writeJson(endPayload)));
        } catch (IOException ignored) {
            // tool_end is best-effort UX; failure here must not abort the stream.
        }
    }
}
