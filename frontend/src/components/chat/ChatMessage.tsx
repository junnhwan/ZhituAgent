import { motion, AnimatePresence } from "framer-motion";
import { Bot, User, AlertCircle, RotateCcw, BookOpen, ChevronDown, ChevronRight } from "lucide-react";
import { useState } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { MessageState, StreamingPhase } from "../../hooks/types";
import type { SnippetInfo } from "../../types/api";
import StreamingCursor from "./StreamingCursor";
import ToolCallCard from "./ToolCallCard";
import "./ChatMessage.css";

const PHASE_LABEL: Record<StreamingPhase, string> = {
  retrieving: "正在检索知识库",
  "calling-tool": "调用工具中",
  generating: "正在生成回答",
  "supervisor-routing": "调度中",
  agent: "处理中",
  "final-writing": "正在生成最终报告",
};

const AGENT_LABEL: Record<string, string> = {
  AlertTriageAgent: "告警分析中",
  LogQueryAgent: "日志查询中",
  ReportAgent: "报告生成中",
};

function phaseText(
  phase: StreamingPhase | undefined,
  toolName: string | undefined,
  agentName: string | undefined,
): string {
  if (!phase) return "思考中";
  if (phase === "calling-tool" && toolName) return `调用工具 ${toolName}`;
  if (phase === "agent" && agentName) {
    return AGENT_LABEL[agentName] ?? `${agentName} 处理中`;
  }
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
  const snippets = msg.trace?.retrievedSnippets ?? [];
  const hasSnippets = snippets.length > 0;

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
          {!isUser && msg.toolCalls && msg.toolCalls.length > 0 && (
            <div className="cm-tool-calls">
              {msg.toolCalls.map((tc) => (
                <ToolCallCard key={tc.toolCallId} toolCall={tc} />
              ))}
            </div>
          )}
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
              <span className="cm-phase-text">{phaseText(msg.phase, msg.toolName, msg.agentName)}…</span>
            </span>
          ) : (
            <div className="cm-text cm-md">
              <Markdown remarkPlugins={[remarkGfm]}>{msg.content}</Markdown>
            </div>
          )}
          {msg.isStreaming && msg.content && <StreamingCursor />}
        </div>

        {!isUser && hasSnippets && <SnippetSources snippets={snippets} />}

        <span className="cm-role">{isUser ? "你" : "Agent"}</span>
      </div>
    </motion.div>
  );
}

function SnippetSources({ snippets }: { snippets: SnippetInfo[] }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="cm-snippets">
      <button
        type="button"
        className="cm-snippets-toggle"
        onClick={() => setExpanded((v) => !v)}
      >
        <BookOpen size={12} />
        <span>引用来源 ({snippets.length})</span>
        {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
      </button>
      <AnimatePresence>
        {expanded && (
          <motion.div
            className="cm-snippets-list"
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.2 }}
          >
            {snippets.map((s, i) => (
              <div key={i} className="cm-snippet">
                <div className="cm-snippet-header">
                  <span className="cm-snippet-source">{s.source}</span>
                  <span className="cm-snippet-score">score: {s.score.toFixed(3)}</span>
                </div>
                <div className="cm-snippet-content">{s.content}</div>
              </div>
            ))}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
