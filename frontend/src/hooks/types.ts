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
}
