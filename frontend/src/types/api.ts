export interface SessionResponse {
  sessionId: string;
  userId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface SessionDetailResponse {
  session: SessionResponse;
  summary: string | null;
  recentMessages: ChatMessageView[];
}

export interface ChatMessageView {
  role: "user" | "assistant";
  content: string;
  timestamp: string;
}

export interface ChatRequest {
  sessionId: string;
  userId: string;
  message: string;
  metadata?: Record<string, unknown>;
}

export interface ChatResponse {
  sessionId: string;
  answer: string;
  trace: TraceInfo;
}

export interface TraceInfo {
  path: string;
  retrievalHit: boolean;
  toolUsed: boolean;
}

export interface SessionCreateRequest {
  userId: string;
  title?: string;
}

export interface ApiErrorResponse {
  code: string;
  message: string;
  requestId: string | null;
}
