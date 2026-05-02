import type { AlertPayload } from "../sre/types";

export interface AlertSupervisorDecision {
  round: number;
  next: string;
  reason: string;
  latencyMs: number;
}

export interface AlertAgentExecution {
  agentName: string;
  iterations: number;
  toolsUsed: string[];
  latencyMs: number;
}

export interface AlertReport {
  traceId: string;
  sessionId: string;
  reportMarkdown: string;
  agentTrail: string[];
  supervisorDecisions: AlertSupervisorDecision[];
  executions: AlertAgentExecution[];
  rounds: number;
  reachedMaxRounds: boolean;
  latencyMs: number;
}

export type AlertStagePhase = "supervisor-routing" | "agent" | "final-writing";

export interface AlertStageEvent {
  phase: AlertStagePhase;
  detail: { agentName?: string; round: number };
}

export interface AlertStreamCallbacks {
  onStart: (sessionId: string, traceId: string) => void;
  onStage: (event: AlertStageEvent) => void;
  onReport: (report: AlertReport) => void;
  onError: (code: string, message: string) => void;
  onComplete: () => void;
}

export function streamAlert(payload: AlertPayload, callbacks: AlertStreamCallbacks): AbortController {
  const controller = new AbortController();

  (async () => {
    const res = await fetch("/api/alert/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    if (!res.ok) {
      if (res.status === 404 || res.status === 500) {
        callbacks.onError(
          "MULTI_AGENT_DISABLED",
          `HTTP ${res.status} — 后端 multi-agent 未启用。启动时加 --zhitu.app.multi-agent-enabled=true。`,
        );
      } else {
        callbacks.onError("HTTP_ERROR", `HTTP ${res.status}`);
      }
      return;
    }

    const reader = res.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let settled = false;

    const ensureSettled = () => {
      if (settled) return;
      settled = true;
      callbacks.onError("STREAM_ABORTED", "流异常断开,未收到结束事件");
    };

    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const blocks = buffer.split("\n\n");
        buffer = blocks.pop() ?? "";

        for (const block of blocks) {
          const lines = block.split("\n");
          const eventLine = lines.find((l) => l.startsWith("event:"));
          const dataLine = lines.find((l) => l.startsWith("data:"));
          if (!eventLine || !dataLine) continue;

          const eventName = eventLine.replace("event:", "").trim();
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const data = JSON.parse(dataLine.replace("data:", "").trim()) as any;

          switch (eventName) {
            case "start":
              callbacks.onStart(data.sessionId, data.traceId);
              break;
            case "stage":
              callbacks.onStage({
                phase: data.phase,
                detail: { agentName: data.detail?.agentName, round: data.detail?.round ?? 0 },
              });
              break;
            case "report":
              callbacks.onReport(data as AlertReport);
              break;
            case "complete":
              settled = true;
              callbacks.onComplete();
              break;
            case "error":
              settled = true;
              callbacks.onError(data.code ?? "ALERT_STREAM_ERROR", data.message ?? "");
              break;
          }
        }
      }
      ensureSettled();
    } catch (err: unknown) {
      if (controller.signal.aborted) return;
      const message = err instanceof Error ? err.message : String(err);
      if (!settled) callbacks.onError("STREAM_FAILED", message);
    }
  })().catch((err: unknown) => {
    if (err instanceof DOMException && err.name === "AbortError") return;
    const message = err instanceof Error ? err.message : String(err);
    callbacks.onError("STREAM_FAILED", message);
  });

  return controller;
}
