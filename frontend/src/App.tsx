import { useReducer, useState, useCallback, useEffect, useRef } from "react";
import MeshGradient, { MeshGradientStyles } from "./components/background/MeshGradient";
import NoiseOverlay, { NoiseOverlayStyles } from "./components/background/NoiseOverlay";
import AppShell from "./components/layout/AppShell";
import Sidebar from "./components/layout/Sidebar";
import Workspace from "./components/layout/Workspace";
import TracePanel from "./components/layout/TracePanel";
import ChatPanel from "./components/chat/ChatPanel";
import Composer from "./components/composer/Composer";
import { appReducer, useSessionManager } from "./hooks/useSessionManager";
import { useStreamingChat, type TraceDisplay } from "./hooks/useStreamingChat";

export default function App() {
  const [state, dispatch] = useReducer(appReducer, {
    sessions: [] as { sessionId: string; userId: string; title: string; messages: never[] }[],
    activeSessionId: null as string | null,
    sending: false,
  });

  const [trace, setTrace] = useState<TraceDisplay>({
    path: "direct-answer",
    retrievalHit: false,
    toolUsed: false,
    status: "idle",
  });

  const { handleNewSession, handleSelectSession, restoreLastSession, getActiveSession } =
    useSessionManager(state, dispatch);

  const { send } = useStreamingChat(dispatch, setTrace);

  const handleSend = useCallback(
    (message: string) => {
      const s = getActiveSession();
      if (!s) return;
      send(s.sessionId, s.userId, message);
    },
    [getActiveSession, send],
  );

  const initialized = useRef(false);
  useEffect(() => {
    if (initialized.current) return;
    initialized.current = true;

    restoreLastSession().then((restored) => {
      if (!restored) {
        handleNewSession().catch(() => setTrace((t) => ({ ...t, status: "error" })));
      }
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activeSession = getActiveSession();
  const activeIdx = state.sessions.findIndex((s) => s.sessionId === state.activeSessionId);

  return (
    <>
      <MeshGradient />
      <MeshGradientStyles />
      <NoiseOverlay />
      <NoiseOverlayStyles />
      <AppShell
        sidebar={
          <Sidebar
            sessions={state.sessions}
            activeIdx={activeIdx}
            onNew={handleNewSession}
            onSelect={(i) => handleSelectSession(state.sessions[i].sessionId)}
          />
        }
        main={
          <Workspace
            title={activeSession?.title ?? "新对话"}
            sessionId={state.activeSessionId}
          >
            <ChatPanel messages={activeSession?.messages ?? []} />
          </Workspace>
        }
        aside={<TracePanel trace={trace} />}
        composer={<Composer sending={state.sending} onSend={handleSend} />}
      />
    </>
  );
}
