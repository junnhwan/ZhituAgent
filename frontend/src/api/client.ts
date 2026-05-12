import type { ApiErrorResponse } from "../types/api";

export class ApiError extends Error {
  code: string;
  requestId: string;
  category: string;

  constructor(code: string, message: string, requestId: string, category: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.requestId = requestId;
    this.category = category;
  }
}

export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  };

  // Inject JWT token if present
  const token = localStorage.getItem("zhitu_token");
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(path, {
    ...options,
    headers,
  });

  if (!res.ok) {
    // Handle 401 Unauthorized - clear token and redirect to login
    if (res.status === 401) {
      localStorage.removeItem("zhitu_token");
      window.location.href = "/login";
      throw new ApiError("UNAUTHORIZED", "Session expired", "", "auth");
    }

    let code = "UNKNOWN";
    let message = `HTTP ${res.status}`;
    let requestId = "";
    let category = "unexpected";

    try {
      const body = (await res.json()) as ApiErrorResponse;
      code = body.code ?? code;
      message = body.message ?? message;
      requestId = body.requestId ?? "";
      category = body.category ?? category;
    } catch {
      // body wasn't JSON
    }

    throw new ApiError(code, message, requestId, category);
  }

  return res.json() as Promise<T>;
}
