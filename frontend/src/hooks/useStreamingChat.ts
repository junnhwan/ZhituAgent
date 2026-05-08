import { useCallback, useRef } from "react";
import { streamChat } from "../api/chat";
import type { TraceInfo } from "../types/api";
import type { PendingToolCall } from "../types/events";
import type { AppAction } from "./useSessionManager";

export interface TraceDisplay extends TraceInfo {
  status: "idle" | "streaming" | "complete" | "error";
}

const emptyTrace: Omit<TraceDisplay, "status"> = {
  path: "direct-answer",
  retrievalHit: false,
  toolUsed: false,
  retrievalMode: "dense",
  contextStrategy: "recent-summary",
  requestId: "",
  latencyMs: 0,
  snippetCount: 0,
  topSource: "",
  topScore: 0,
  retrievalCandidateCount: 0,
  rerankModel: "",
  rerankTopScore: 0,
  factCount: 0,
  inputTokenEstimate: 0,
  outputTokenEstimate: 0,
  retrievedSources: [],
  retrievedSnippets: [],
  traceId: "",
  spans: [],
};

export function emptyTraceDisplay(): TraceDisplay {
  return { ...emptyTrace, status: "idle" };
}

export function useStreamingChat(
  dispatch: React.Dispatch<AppAction>,
  onTraceChange: (trace: TraceDisplay) => void,
  onToolCallPending?: (pending: PendingToolCall) => void,
) {
  const abortRef = useRef<AbortController | null>(null);
  const rafRef = useRef<number>(0);
  const pendingContent = useRef("");
  const pendingSessionId = useRef("");
  const lastRequest = useRef<{ sessionId: string; userId: string; message: string } | null>(null);

  const flushUpdate = useCallback(() => {
    if (pendingContent.current && pendingSessionId.current) {
      dispatch({
        type: "UPDATE_STREAMING_MESSAGE",
        payload: {
          sessionId: pendingSessionId.current,
          content: pendingContent.current,
        },
      });
    }
    rafRef.current = 0;
  }, [dispatch]);

  const scheduleFlush = useCallback(() => {
    if (!rafRef.current) {
      rafRef.current = requestAnimationFrame(flushUpdate);
    }
  }, [flushUpdate]);

  const dispatchSend = useCallback(
    (
      sessionId: string,
      userId: string,
      message: string,
      metadata: Record<string, unknown>,
      treatAsResume: boolean,
    ) => {
      abortRef.current?.abort();
      cancelAnimationFrame(rafRef.current);

      pendingContent.current = "";
      pendingSessionId.current = sessionId;

      if (!treatAsResume) {
        dispatch({
          type: "ADD_MESSAGE",
          payload: { sessionId, message: { role: "user", content: message } },
        });
      }

      dispatch({
        type: "ADD_MESSAGE",
        payload: { sessionId, message: { role: "assistant", content: "", isStreaming: true } },
      });

      dispatch({ type: "SET_SENDING", payload: true });
      onTraceChange({ ...emptyTrace, status: "streaming" });

      const controller = streamChat(
        { sessionId, userId, message, metadata },
        {
          onStart: () => {},
          onToken: (token) => {
            pendingContent.current += token;
            scheduleFlush();
          },
          onStage: (phase, detail) => {
            dispatch({
              type: "UPDATE_STREAMING_PHASE",
              payload: { sessionId, phase, toolName: detail?.toolName },
            });
          },
          onToolStart: (event) => {
            dispatch({
              type: "ADD_TOOL_CALL",
              payload: {
                sessionId,
                toolCall: {
                  toolCallId: event.toolCallId,
                  name: event.name,
                  source: event.source,
                  server: event.server,
                  transport: event.transport,
                  args: event.args,
                  status: "running",
                },
              },
            });
          },
          onToolEnd: (event) => {
            dispatch({
              type: "UPDATE_TOOL_CALL",
              payload: {
                sessionId,
                toolCallId: event.toolCallId,
                patch: {
                  status: event.status,
                  durationMs: event.durationMs,
                  resultPreview: event.resultPreview,
                },
              },
            });
          },
          onComplete: (trace: TraceInfo) => {
            cancelAnimationFrame(rafRef.current);
            dispatch({
              type: "FINALIZE_STREAMING_MESSAGE",
              payload: { sessionId, content: pendingContent.current, trace },
            });
            dispatch({ type: "SET_SENDING", payload: false });
            onTraceChange({ ...trace, status: "complete" });
          },
          onError: (code, errMessage, requestId) => {
            cancelAnimationFrame(rafRef.current);
            if (pendingContent.current) {
              dispatch({
                type: "FINALIZE_STREAMING_MESSAGE",
                payload: { sessionId, content: pendingContent.current },
              });
            } else {
              dispatch({
                type: "MARK_STREAMING_ERROR",
                payload: {
                  sessionId,
                  error: { code: code || "UNKNOWN", message: errMessage || "未知错误", requestId },
                },
              });
            }
            dispatch({ type: "SET_SENDING", payload: false });
            onTraceChange({ ...emptyTrace, status: "error" });
          },
          onToolCallPending: (pending) => {
            onToolCallPending?.(pending);
          },
        },
      );

      abortRef.current = controller;
    },
    [dispatch, onTraceChange, onToolCallPending, scheduleFlush],
  );

  const send = useCallback(
    (sessionId: string, userId: string, message: string) => {
      lastRequest.current = { sessionId, userId, message };
      dispatchSend(sessionId, userId, message, { client: "web" }, false);
    },
    [dispatchSend],
  );

  const resendWithApproval = useCallback(
    (approvedToolCallId: string) => {
      const last = lastRequest.current;
      if (!last) return;
      dispatchSend(
        last.sessionId,
        last.userId,
        last.message,
        { client: "web", approvedToolCallId },
        true,
      );
    },
    [dispatchSend],
  );

  const retry = useCallback(() => {
    const last = lastRequest.current;
    if (!last) return;
    dispatchSend(last.sessionId, last.userId, last.message, { client: "web" }, true);
  }, [dispatchSend]);

  const abort = useCallback(() => {
    abortRef.current?.abort();
    cancelAnimationFrame(rafRef.current);
    dispatch({ type: "SET_SENDING", payload: false });
    onTraceChange({ ...emptyTrace, status: "idle" });
  }, [dispatch, onTraceChange]);

  return { send, abort, resendWithApproval, retry };
}
