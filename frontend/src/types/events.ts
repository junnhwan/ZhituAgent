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

/**
 * Tool invocation lifecycle — emitted by the backend right after the router
 * decides to use a tool. Pair {@code tool_start} + {@code tool_end} share a
 * {@code toolCallId} so the frontend can attach the result to the running
 * card.
 *
 * <p>{@code source} is {@code "mcp"} for tools coming from an
 * {@code OfficialSdkMcpClient} (the {@code source / mcpServer / mcpTransport}
 * fields are populated by {@link McpToolAdapter} on the backend) or
 * {@code "builtin"} for in-process tools.
 */
export interface SseToolStartEvent {
  type: "tool_start";
  toolCallId: string;
  name: string;
  source: string;
  server?: string;
  transport?: string;
  args: Record<string, unknown>;
}

export interface SseToolEndEvent {
  type: "tool_end";
  toolCallId: string;
  status: "success" | "error";
  durationMs: number;
  resultPreview: string;
}

export type SseEvent =
  | SseStartEvent
  | SseTokenEvent
  | SseCompleteEvent
  | SseErrorEvent
  | SseStageEvent
  | SseToolStartEvent
  | SseToolEndEvent;

export interface StreamCallbacks {
  onStart: (sessionId: string) => void;
  onToken: (token: string) => void;
  onStage?: (phase: StreamingPhase, detail?: { toolName?: string; agentName?: string; round?: number }) => void;
  onComplete: (trace: TraceInfo) => void;
  onError: (code: string, message: string, requestId?: string) => void;
  onToolCallPending?: (pending: PendingToolCall) => void;
  onToolStart?: (event: SseToolStartEvent) => void;
  onToolEnd?: (event: SseToolEndEvent) => void;
}
