import { useCallback, useRef } from "react";
import { createSession, getSession } from "../api/sessions";
import type { SessionState, MessageState } from "./types";

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
  | { type: "FINALIZE_STREAMING_MESSAGE"; payload: { sessionId: string; content: string } }
  | { type: "SET_SENDING"; payload: boolean }
  | { type: "LOAD_SESSION_HISTORY"; payload: { sessionId: string; messages: MessageState[] } }
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

    case "FINALIZE_STREAMING_MESSAGE": {
      const sessions = state.sessions.map((s) => {
        if (s.sessionId !== action.payload.sessionId) return s;
        const msgs = [...s.messages];
        const last = msgs[msgs.length - 1];
        if (last?.isStreaming) {
          msgs[msgs.length - 1] = { ...last, content: action.payload.content, isStreaming: false };
        }
        return { ...s, messages: msgs };
      });
      return { ...state, sessions };
    }

    case "SET_SENDING":
      return { ...state, sending: action.payload };

    case "LOAD_SESSION_HISTORY": {
      const sessions = state.sessions.map((s) =>
        s.sessionId === action.payload.sessionId
          ? { ...s, messages: action.payload.messages }
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
    };
    dispatch({ type: "ADD_SESSION", payload: session });
    // Persist latest session id
    try { localStorage.setItem("zhitu_last_session", resp.sessionId); } catch { /* noop */ }
  }, [dispatch]);

  const handleSelectSession = useCallback(
    async (id: string) => {
      dispatch({ type: "SET_ACTIVE_SESSION", payload: id });

      // Fetch history from backend for the target session
      const target = state.sessions.find((s) => s.sessionId === id);
      if (!target || target.messages.length > 0) return; // already loaded

      try {
        const detail = await getSession(id);
        const messages: MessageState[] = detail.recentMessages.map((m) => ({
          role: m.role,
          content: m.content,
          timestamp: m.timestamp,
        }));
        dispatch({ type: "LOAD_SESSION_HISTORY", payload: { sessionId: id, messages } });
      } catch {
        // Backend unavailable or session expired — leave messages empty
      }

      try { localStorage.setItem("zhitu_last_session", id); } catch { /* noop */ }
    },
    [dispatch, state.sessions],
  );

  const restoreLastSession = useCallback(async (): Promise<boolean> => {
    let lastId: string | null = null;
    try { lastId = localStorage.getItem("zhitu_last_session"); } catch { /* noop */ }
    if (!lastId) return false;

    try {
      const detail = await getSession(lastId);
      const messages: MessageState[] = detail.recentMessages.map((m) => ({
        role: m.role,
        content: m.content,
        timestamp: m.timestamp,
      }));
      dispatch({
        type: "RESTORE_SESSION",
        payload: {
          sessionId: detail.session.sessionId,
          userId: detail.session.userId,
          title: detail.session.title,
          messages,
        },
      });
      return true;
    } catch {
      // Session expired or backend unreachable
      try { localStorage.removeItem("zhitu_last_session"); } catch { /* noop */ }
      return false;
    }
  }, [dispatch]);

  const getActiveSession = useCallback((): SessionState | undefined => {
    return state.sessions.find((s) => s.sessionId === state.activeSessionId);
  }, [state.sessions, state.activeSessionId]);

  return { handleNewSession, handleSelectSession, restoreLastSession, getActiveSession };
}
