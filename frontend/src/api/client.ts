import type { ApiErrorResponse } from "../types/api";

export class ApiError extends Error {
  code: string;
  requestId: string | null;

  constructor(code: string, message: string, requestId: string | null) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.requestId = requestId;
  }
}

export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });

  if (!res.ok) {
    let code = "UNKNOWN";
    let message = `HTTP ${res.status}`;
    let requestId: string | null = null;

    try {
      const body = (await res.json()) as ApiErrorResponse;
      code = body.code ?? code;
      message = body.message ?? message;
      requestId = body.requestId ?? null;
    } catch {
      // body wasn't JSON, use defaults
    }

    throw new ApiError(code, message, requestId);
  }

  return res.json() as Promise<T>;
}
