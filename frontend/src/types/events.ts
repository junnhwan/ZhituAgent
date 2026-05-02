import type { TraceInfo } from "./api";

export interface SseStartEvent {
  type: "start";
  sessionId: string;
}

export interface SseTokenEvent {
  type: "token";
  content: string;
}

export type SseCompleteEvent = TraceInfo;

export interface SseErrorEvent {
  type: "error";
  code: string;
  message: string;
  requestId?: string;
}

export type StreamingPhase =
  | "retrieving"
  | "calling-tool"
  | "generating"
  | "supervisor-routing"
  | "agent"
  | "final-writing";

export interface SseStageEvent {
  type: "stage";
  phase: StreamingPhase;
  detail?: { toolName?: string; agentName?: string; round?: number };
}

export interface PendingToolCall {
  pendingId: string;
  toolName: string;
  status: "AWAITING_APPROVAL";
  arguments: Record<string, unknown>;
}

export type SseEvent = SseStartEvent | SseTokenEvent | SseCompleteEvent | SseErrorEvent | SseStageEvent;

export interface StreamCallbacks {
  onStart: (sessionId: string) => void;
  onToken: (token: string) => void;
  onStage?: (phase: StreamingPhase, detail?: { toolName?: string; agentName?: string; round?: number }) => void;
  onComplete: (trace: TraceInfo) => void;
  onError: (code: string, message: string, requestId?: string) => void;
  onToolCallPending?: (pending: PendingToolCall) => void;
}
