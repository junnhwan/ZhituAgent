import { motion } from "framer-motion";
import { Bot, User, AlertCircle, RotateCcw } from "lucide-react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { MessageState, StreamingPhase } from "../../hooks/types";
import StreamingCursor from "./StreamingCursor";
import "./ChatMessage.css";

const PHASE_LABEL: Record<StreamingPhase, string> = {
  retrieving: "正在检索知识库",
  "calling-tool": "调用工具中",
  generating: "正在生成回答",
};

function phaseText(phase: StreamingPhase | undefined, toolName: string | undefined): string {
  if (!phase) return "思考中";
  if (phase === "calling-tool" && toolName) return `调用工具 ${toolName}`;
  return PHASE_LABEL[phase];
}

export default function ChatMessage({
  msg,
  index,
  onRetry,
}: {
  msg: MessageState;
  index: number;
  onRetry?: () => void;
}) {
  const isUser = msg.role === "user";
  const isError = !isUser && !!msg.error;
  const showPlaceholder = !isUser && msg.isStreaming && !msg.content;

  return (
    <motion.div
      className={`cm ${msg.role}${isError ? " is-error" : ""}`}
      initial={{ opacity: 0, y: 16, scale: 0.97 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ duration: 0.35, delay: index * 0.03 }}
    >
      <div className={`cm-avatar ${msg.role}${isError ? " is-error" : ""}`}>
        {isUser ? <User size={16} /> : isError ? <AlertCircle size={16} /> : <Bot size={16} />}
      </div>

      <div className="cm-bubble-wrap">
        <div className="cm-bubble">
          {isUser ? (
            <span className="cm-text">{msg.content}</span>
          ) : isError ? (
            <div className="cm-error">
              <div className="cm-error-title">生成失败 · {msg.error?.code}</div>
              <div className="cm-error-message">{msg.error?.message}</div>
              {msg.error?.requestId && (
                <div className="cm-error-meta">requestId: {msg.error.requestId}</div>
              )}
              {onRetry && (
                <button type="button" className="cm-retry" onClick={onRetry}>
                  <RotateCcw size={13} /> 重试
                </button>
              )}
            </div>
          ) : showPlaceholder ? (
            <span className="cm-phase">
              <motion.span
                className="cm-phase-dot"
                animate={{ opacity: [0.3, 1, 0.3], scale: [0.85, 1.15, 0.85] }}
                transition={{ repeat: Infinity, duration: 1.2, ease: "easeInOut" }}
              />
              <span className="cm-phase-text">{phaseText(msg.phase, msg.toolName)}…</span>
            </span>
          ) : (
            <div className="cm-text cm-md">
              <Markdown remarkPlugins={[remarkGfm]}>{msg.content}</Markdown>
            </div>
          )}
          {msg.isStreaming && msg.content && <StreamingCursor />}
        </div>
        <span className="cm-role">{isUser ? "你" : "Agent"}</span>
      </div>
    </motion.div>
  );
}
