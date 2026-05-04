export interface SessionState {
  sessionId: string;
  userId: string;
  title: string;
  messages: MessageState[];
  summary: string | null;
  facts: string[];
}

export type StreamingPhase =
  | "retrieving"
  | "calling-tool"
  | "generating"
  | "supervisor-routing"
  | "agent"
  | "final-writing";

export interface StreamingError {
  code: string;
  message: string;
  requestId?: string;
}

/**
 * One tool invocation surfaced through the SSE {@code tool_start / tool_end}
 * pair. Keyed by toolCallId so {@code <ToolCallCard>} can render the 4-state
 * pill (pending / running / success / error) and 🔌 server badge for
 * MCP-sourced tools (when {@code source === "mcp"}).
 */
export interface ToolCallState {
  toolCallId: string;
  name: string;
  /** {@code "mcp"} = real MCP server tool, {@code "builtin"} = in-process. */
  source: string;
  /** MCP server logical name (e.g. "tavily", "baidu"). Only set when source=mcp. */
  server?: string;
  /** {@code "stdio"} | {@code "streamable-http"} | {@code "sse"}. Only when mcp. */
  transport?: string;
  args: Record<string, unknown>;
  status: "running" | "success" | "error";
  durationMs?: number;
  resultPreview?: string;
}

export interface MessageState {
  role: "user" | "assistant";
  content: string;
  timestamp?: string;
  isStreaming?: boolean;
  phase?: StreamingPhase;
  toolName?: string;
  agentName?: string;
  round?: number;
  error?: StreamingError;
  /** Tool invocations bound to this message (rendered inline as ToolCallCards). */
  toolCalls?: ToolCallState[];
}
