import type { PendingToolCall } from "../types/events";

export async function approveToolCall(pendingId: string): Promise<PendingToolCall> {
  const res = await fetch(`/api/tool-calls/${encodeURIComponent(pendingId)}/approve`, {
    method: "POST",
  });
  if (!res.ok) {
    throw new Error(`approve failed: HTTP ${res.status}`);
  }
  return res.json() as Promise<PendingToolCall>;
}

export async function denyToolCall(pendingId: string): Promise<PendingToolCall> {
  const res = await fetch(`/api/tool-calls/${encodeURIComponent(pendingId)}/deny`, {
    method: "POST",
  });
  if (!res.ok) {
    throw new Error(`deny failed: HTTP ${res.status}`);
  }
  return res.json() as Promise<PendingToolCall>;
}

export async function listPendingToolCalls(): Promise<PendingToolCall[]> {
  const res = await fetch("/api/tool-calls/pending");
  if (!res.ok) {
    throw new Error(`list pending failed: HTTP ${res.status}`);
  }
  return res.json() as Promise<PendingToolCall[]>;
}
