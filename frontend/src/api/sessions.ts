import { request } from "./client";
import type { SessionCreateRequest, SessionDetailResponse, SessionResponse } from "../types/api";

export function createSession(
  userId: string,
  title?: string,
): Promise<SessionResponse> {
  const body: SessionCreateRequest = { userId, title };
  return request<SessionResponse>("/api/sessions", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function getSession(
  sessionId: string,
): Promise<SessionDetailResponse> {
  return request<SessionDetailResponse>(`/api/sessions/${sessionId}`);
}
