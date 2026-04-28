import { useCallback, useRef } from "react";
import { streamChat } from "../api/chat";
import type { TraceInfo } from "../types/api";
import type { AppAction } from "./useSessionManager";

export interface TraceDisplay {
  path: string;
  retrievalHit: boolean;
  toolUsed: boolean;
  status: "idle" | "streaming" | "complete" | "error";
}

export function useStreamingChat(
  dispatch: React.Dispatch<AppAction>,
  onTraceChange: (trace: TraceDisplay) => void,
) {
  const abortRef = useRef<AbortController | null>(null);
  const rafRef = useRef<number>(0);
  const pendingContent = useRef("");
  const pendingSessionId = useRef("");

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

  const send = useCallback(
    (sessionId: string, userId: string, message: string) => {
      // Abort any in-flight stream
      abortRef.current?.abort();
      cancelAnimationFrame(rafRef.current);

      pendingContent.current = "";
      pendingSessionId.current = sessionId;

      // Add user message
      dispatch({
        type: "ADD_MESSAGE",
        payload: {
          sessionId,
          message: { role: "user", content: message },
        },
      });

      // Add empty assistant placeholder
      dispatch({
        type: "ADD_MESSAGE",
        payload: {
          sessionId,
          message: { role: "assistant", content: "", isStreaming: true },
        },
      });

      dispatch({ type: "SET_SENDING", payload: true });
      onTraceChange({ path: "direct-answer", retrievalHit: false, toolUsed: false, status: "streaming" });

      const controller = streamChat(
        { sessionId, userId, message, metadata: { client: "web" } },
        {
          onStart: () => {},
          onToken: (token) => {
            pendingContent.current += token;
            scheduleFlush();
          },
          onComplete: (trace: TraceInfo) => {
            // Flush final content
            cancelAnimationFrame(rafRef.current);
            const finalContent = pendingContent.current;
            dispatch({
              type: "FINALIZE_STREAMING_MESSAGE",
              payload: { sessionId, content: finalContent },
            });
            dispatch({ type: "SET_SENDING", payload: false });
            onTraceChange({
              path: trace.path,
              retrievalHit: trace.retrievalHit,
              toolUsed: trace.toolUsed,
              status: "complete",
            });
          },
          onError: (code, msg) => {
            cancelAnimationFrame(rafRef.current);
            const finalContent = pendingContent.current || `Error: ${msg} (${code})`;
            dispatch({
              type: "FINALIZE_STREAMING_MESSAGE",
              payload: { sessionId, content: finalContent },
            });
            dispatch({ type: "SET_SENDING", payload: false });
            onTraceChange({ path: "direct-answer", retrievalHit: false, toolUsed: false, status: "error" });
          },
        },
      );

      abortRef.current = controller;
    },
    [dispatch, onTraceChange, scheduleFlush],
  );

  const abort = useCallback(() => {
    abortRef.current?.abort();
    cancelAnimationFrame(rafRef.current);
  }, []);

  return { send, abort };
}
