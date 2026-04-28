import type { ReactNode } from "react";
import { Sparkles } from "lucide-react";
import "./Workspace.css";

export default function Workspace({
  title,
  sessionId,
  children,
}: {
  title: string;
  sessionId: string | null;
  children: ReactNode;
}) {
  return (
    <>
      <header className="wk-header">
        <div className="wk-header-left">
          <Sparkles size={18} className="wk-header-icon" />
          <h1 className="wk-header-title">{title}</h1>
        </div>
        <span className="wk-header-id">{sessionId ? `#${sessionId.slice(0, 8)}` : ""}</span>
      </header>

      <div className="wk-body">{children}</div>

      {/* Composer dock area */}
      <div className="wk-composer-dock" />
    </>
  );
}
