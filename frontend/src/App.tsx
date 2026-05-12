import { useReducer, useState, useCallback, useEffect, useRef } from "react";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { AuthProvider, useAuth } from "./contexts/AuthContext";
import { LoginPage } from "./components/auth/LoginPage";
import { RegisterPage } from "./components/auth/RegisterPage";
import MeshGradient, { MeshGradientStyles } from "./components/background/MeshGradient";
import NoiseOverlay, { NoiseOverlayStyles } from "./components/background/NoiseOverlay";
import AppShell from "./components/layout/AppShell";
import Sidebar from "./components/layout/Sidebar";
import Workspace from "./components/layout/Workspace";
import TracePanel from "./components/layout/TracePanel";
import ChatPanel from "./components/chat/ChatPanel";
import Composer from "./components/composer/Composer";
import KnowledgeModal from "./components/knowledge/KnowledgeModal";
import FileUploadModal from "./components/knowledge/FileUploadModal";
import SettingsModal from "./components/knowledge/SettingsModal";
import HitlConfirmPanel from "./components/hitl/HitlConfirmPanel";
import SreDemoPanel from "./components/sre/SreDemoPanel";
import { appReducer, useSessionManager } from "./hooks/useSessionManager";
import { useStreamingChat, emptyTraceDisplay, type TraceDisplay } from "./hooks/useStreamingChat";
import type { PendingToolCall } from "./types/events";
import { approveToolCall, denyToolCall } from "./api/tools";

type View = "chat" | "sre";

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function MainApp() {
  const [state, dispatch] = useReducer(appReducer, {
    sessions: [] as import("./hooks/types").SessionState[],
    activeSessionId: null as string | null,
    sending: false,
  });

  const [trace, setTrace] = useState<TraceDisplay>(emptyTraceDisplay());
  const [knowledgeOpen, setKnowledgeOpen] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [pendingToolCall, setPendingToolCall] = useState<PendingToolCall | null>(null);
  const [view, setView] = useState<View>("chat");
  const [autoStartAlertId, setAutoStartAlertId] = useState<string | null>(null);

  const { handleNewSession, handleSelectSession, restoreLastSession, getActiveSession } =
    useSessionManager(state, dispatch);

  const { send, abort, resendWithApproval, retry } = useStreamingChat(
    dispatch,
    setTrace,
    (pending) => setPendingToolCall(pending),
  );

  const handleSend = useCallback(
    (message: string) => {
      const s = getActiveSession();
      if (!s) return;
      send(s.sessionId, s.userId, message);
    },
    [getActiveSession, send],
  );

  const handleSwitchToSre = useCallback((fixtureId: string) => {
    setAutoStartAlertId(fixtureId);
    setView("sre");
  }, []);

  const handleApprove = useCallback(
    async (pendingId: string) => {
      await approveToolCall(pendingId);
      setPendingToolCall(null);
      resendWithApproval(pendingId);
    },
    [resendWithApproval],
  );

  const handleDeny = useCallback(async (pendingId: string) => {
    await denyToolCall(pendingId);
    setPendingToolCall(null);
  }, []);

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
            onOpenKnowledge={() => setKnowledgeOpen(true)}
            onOpenSettings={() => setSettingsOpen(true)}
            onOpenUpload={() => setUploadOpen(true)}
            view={view}
            onViewChat={() => setView("chat")}
            onViewSre={() => setView("sre")}
          />
        }
        main={
          view === "chat" ? (
            <Workspace
              title={activeSession?.title ?? "新对话"}
              sessionId={state.activeSessionId}
              facts={activeSession?.facts ?? []}
            >
              <ChatPanel
                messages={activeSession?.messages ?? []}
                onSuggestionClick={handleSend}
                onRetry={retry}
                onSwitchToSre={handleSwitchToSre}
              />
            </Workspace>
          ) : (
            <SreDemoPanel
              autoStartAlertId={autoStartAlertId}
              onAutoStartConsumed={() => setAutoStartAlertId(null)}
            />
          )
        }
        aside={view === "chat" ? <TracePanel trace={trace} /> : <TracePanel trace={emptyTraceDisplay()} />}
        composer={view === "chat" ? <Composer sending={state.sending} onSend={handleSend} onAbort={abort} /> : null}
      />
      <KnowledgeModal open={knowledgeOpen} onClose={() => setKnowledgeOpen(false)} />
      <FileUploadModal open={uploadOpen} onClose={() => setUploadOpen(false)} />
      <SettingsModal open={settingsOpen} onClose={() => setSettingsOpen(false)} sessionId={state.activeSessionId} />
      <HitlConfirmPanel
        pending={pendingToolCall}
        onApprove={handleApprove}
        onDeny={handleDeny}
      />
    </>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route
            path="/*"
            element={
              <ProtectedRoute>
                <MainApp />
              </ProtectedRoute>
            }
          />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}
