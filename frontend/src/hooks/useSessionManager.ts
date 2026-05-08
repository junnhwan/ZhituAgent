import { useCallback, useRef } from "react";
import { createSession, getSession } from "../api/sessions";
import type { SessionState, MessageState, StreamingPhase, StreamingError, ToolCallState } from "./types";

export interface AppState {
  sessions: SessionState[];
  activeSessionId: string | null;
  sending: boolean;
}

export type AppAction =
  | { type: "ADD_SESSION"; payload: SessionState }
  | { type: "SET_ACTIVE_SESSION"; payload: string }
  | { type: "ADD_MESSAGE"; payload: { sessionId: string; message: MessageState } }
  | { type: "UPDATE_STREAMING_MESSAGE"; payload: { sessionId: string; content: string } }
  | { type: "UPDATE_STREAMING_PHASE"; payload: { sessionId: string; phase: StreamingPhase; toolName?: string } }
  | { type: "FINALIZE_STREAMING_MESSAGE"; payload: { sessionId: string; content: string; trace?: import("../types/api").TraceInfo } }
  | { type: "MARK_STREAMING_ERROR"; payload: { sessionId: string; error: StreamingError } }
  | { type: "ADD_TOOL_CALL"; payload: { sessionId: string; toolCall: ToolCallState } }
  | { type: "UPDATE_TOOL_CALL"; payload: { sessionId: string; toolCallId: string; patch: Partial<ToolCallState> } }
  | { type: "SET_SENDING"; payload: boolean }
  | { type: "LOAD_SESSION_DETAIL"; payload: { sessionId: string; messages: MessageState[]; summary: string | null; facts: string[] } }
  | { type: "RESTORE_SESSION"; payload: SessionState };

export function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case "ADD_SESSION":
      return {
        ...state,
        sessions: [action.payload, ...state.sessions],
        activeSessionId: action.payload.sessionId,
      };

    case "SET_ACTIVE_SESSION":
      return { ...state, activeSessionId: action.payload };

    case "ADD_MESSAGE": {
      const sessions = state.sessions.map((s) =>
        s.sessionId === action.payload.sessionId
          ? { ...s, messages: [...s.messages, action.payload.message] }
          : s,
      );
      return { ...state, sessions };
    }

    case "UPDATE_STREAMING_MESSAGE": {
      const sessions = state.sessions.map((s) => {
        if (s.sessionId !== action.payload.sessionId) return s;
        const msgs = [...s.messages];
        const last = msgs[msgs.length - 1];
        if (last?.isStreaming) {
          msgs[msgs.length - 1] = { ...last, content: action.payload.content };
        }
        return { ...s, messages: msgs };
      });
      return { ...state, sessions };
    }

    case "UPDATE_STREAMING_PHASE": {
      const sessions = state.sessions.map((s) => {
        if (s.sessionId !== action.payload.sessionId) return s;
        const msgs = [...s.messages];
        const last = msgs[msgs.length - 1];
        if (last?.isStreaming) {
          msgs[msgs.length - 1] = { ...last, phase: action.payload.phase, toolName: action.payload.toolName };
        }
        return { ...s, messages: msgs };
      });
      return { ...state, sessions };
    }

    case "FINALIZE_STREAMING_MESSAGE": {
      const sessions = state.sessions.map((s) => {
        if (s.sessionId !== action.payload.sessionId) return s;
        const msgs = [...s.messages];
        const last = msgs[msgs.length - 1];
        if (last?.isStreaming) {
          msgs[msgs.length - 1] = {
            ...last,
            content: action.payload.content,
            isStreaming: false,
            phase: undefined,
            trace: action.payload.trace,
          };
        }
        return { ...s, messages: msgs };
      });
      return { ...state, sessions };
    }

    case "MARK_STREAMING_ERROR": {
      const sessions = state.sessions.map((s) => {
        if (s.sessionId !== action.payload.sessionId) return s;
        const msgs = [...s.messages];
        const last = msgs[msgs.length - 1];
        if (last?.isStreaming) {
          msgs[msgs.length - 1] = { ...last, isStreaming: false, phase: undefined, error: action.payload.error };
        }
        return { ...s, messages: msgs };
      });
      return { ...state, sessions };
    }

    case "ADD_TOOL_CALL": {
      // Append a new ToolCallState to the currently-streaming assistant message
      // (the last message in the session). No-op if no streaming target exists
      // — backend should never emit tool_start without an assistant message
      // already added, but guard for safety.
      const sessions = state.sessions.map((s) => {
        if (s.sessionId !== action.payload.sessionId) return s;
        const msgs = [...s.messages];
        const last = msgs[msgs.length - 1];
        if (last?.isStreaming) {
          const existing = last.toolCalls ?? [];
          msgs[msgs.length - 1] = {
            ...last,
            toolCalls: [...existing, action.payload.toolCall],
          };
        }
        return { ...s, messages: msgs };
      });
      return { ...state, sessions };
    }

    case "UPDATE_TOOL_CALL": {
      // Patch a specific ToolCallState by toolCallId on the streaming message.
      // Used by tool_end to flip status running → success/error and add timing
      // / resultPreview. Falls back to no-op if the call wasn't registered.
      const sessions = state.sessions.map((s) => {
        if (s.sessionId !== action.payload.sessionId) return s;
        const msgs = [...s.messages];
        const last = msgs[msgs.length - 1];
        if (last?.isStreaming && last.toolCalls) {
          const updated = last.toolCalls.map((tc) =>
            tc.toolCallId === action.payload.toolCallId
              ? { ...tc, ...action.payload.patch }
              : tc,
          );
          msgs[msgs.length - 1] = { ...last, toolCalls: updated };
        }
        return { ...s, messages: msgs };
      });
      return { ...state, sessions };
    }

    case "SET_SENDING":
      return { ...state, sending: action.payload };

    case "LOAD_SESSION_DETAIL": {
      const sessions = state.sessions.map((s) =>
        s.sessionId === action.payload.sessionId
          ? { ...s, messages: action.payload.messages, summary: action.payload.summary, facts: action.payload.facts }
          : s,
      );
      return { ...state, sessions };
    }

    case "RESTORE_SESSION": {
      const exists = state.sessions.some(
        (s) => s.sessionId === action.payload.sessionId,
      );
      if (exists) {
        return {
          ...state,
          activeSessionId: action.payload.sessionId,
        };
      }
      return {
        ...state,
        sessions: [action.payload, ...state.sessions],
        activeSessionId: action.payload.sessionId,
      };
    }

    default:
      return state;
  }
}

function mapDetailToState(detail: { messages: { role: string; content: string; timestamp: string }[]; summary: string | null; facts: string[] }) {
  return {
    messages: detail.messages.map((m) => ({
      role: m.role as "user" | "assistant",
      content: m.content,
      timestamp: m.timestamp,
    })),
    summary: detail.summary,
    facts: detail.facts,
  };
}

export function useSessionManager(
  state: AppState,
  dispatch: React.Dispatch<AppAction>,
) {
  const sessionCount = useRef(0);

  const handleNewSession = useCallback(async () => {
    sessionCount.current += 1;
    const resp = await createSession("demo-user", `会话 ${sessionCount.current}`);
    const session: SessionState = {
      sessionId: resp.sessionId,
      userId: resp.userId,
      title: resp.title,
      messages: [],
      summary: null,
      facts: [],
    };
    dispatch({ type: "ADD_SESSION", payload: session });
    try { localStorage.setItem("zhitu_last_session", resp.sessionId); } catch { /* noop */ }
  }, [dispatch]);

  const handleSelectSession = useCallback(
    async (id: string) => {
      dispatch({ type: "SET_ACTIVE_SESSION", payload: id });

      // Always re-fetch from backend to get latest summary + facts + messages
      try {
        const detail = await getSession(id);
        const { messages, summary, facts } = mapDetailToState(detail);
        dispatch({ type: "LOAD_SESSION_DETAIL", payload: { sessionId: id, messages, summary, facts } });
      } catch {
        // Backend unavailable — keep whatever we have locally
      }

      try { localStorage.setItem("zhitu_last_session", id); } catch { /* noop */ }
    },
    [dispatch],
  );

  const restoreLastSession = useCallback(async (): Promise<boolean> => {
    let lastId: string | null = null;
    try { lastId = localStorage.getItem("zhitu_last_session"); } catch { /* noop */ }
    if (!lastId) return false;

    try {
      const detail = await getSession(lastId);
      const { messages, summary, facts } = mapDetailToState(detail);
      dispatch({
        type: "RESTORE_SESSION",
        payload: {
          sessionId: detail.session.sessionId,
          userId: detail.session.userId,
          title: detail.session.title,
          messages,
          summary,
          facts,
        },
      });
      return true;
    } catch {
      try { localStorage.removeItem("zhitu_last_session"); } catch { /* noop */ }
      return false;
    }
  }, [dispatch]);

  const getActiveSession = useCallback((): SessionState | undefined => {
    return state.sessions.find((s) => s.sessionId === state.activeSessionId);
  }, [state.sessions, state.activeSessionId]);

  return { handleNewSession, handleSelectSession, restoreLastSession, getActiveSession };
}
