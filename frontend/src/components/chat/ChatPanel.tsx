import { Workflow, Database, Wrench, Repeat } from "lucide-react";
import { motion } from "framer-motion";
import ChatMessage from "./ChatMessage";
import type { MessageState } from "../../hooks/types";
import { useAutoScroll } from "../../hooks/useAutoScroll";
import "./ChatPanel.css";

type Suggestion = {
  icon: React.ReactNode;
  title: string;
  prompt: string;            // 用作卡片副标题文案
  action?: "sre";            // undefined → 走 /api/chat;"sre" → 切到 SRE Demo
  fixtureId?: string;        // action="sre" 时携带的告警 fixture id
};

const SUGGESTIONS: Suggestion[] = [
  {
    icon: <Workflow size={16} />,
    title: "Agent 编排",
    prompt: "支付网关 502 告警，帮我分析根因并出处置报告",
    action: "sre",
    fixtureId: "alert-002",
  },
  {
    icon: <Database size={16} />,
    title: "RAG 知识库",
    prompt: "从知识库找 ES hybrid 检索的实现要点",
  },
  {
    icon: <Wrench size={16} />,
    title: "MCP & 工具",
    prompt: "查一下当前时间和最近 5 分钟的 prod 错误日志",
  },
  {
    icon: <Repeat size={16} />,
    title: "ReAct 循环",
    prompt: "排查为什么 P99 延迟突然飙到 2s",
  },
];

export default function ChatPanel({
  messages,
  onSuggestionClick,
  onRetry,
  onSwitchToSre,
}: {
  messages: MessageState[];
  onSuggestionClick?: (prompt: string) => void;
  onRetry?: () => void;
  onSwitchToSre?: (fixtureId: string) => void;
}) {
  const scrollRef = useAutoScroll(messages);

  return (
    <div className="cp" ref={scrollRef}>
      <div className="cp-inner">
        {messages.length === 0 && (
          <div className="cp-empty">
            <h2 className="cp-empty-title">想从哪里开始？</h2>
            <p className="cp-empty-sub">点一个常用场景，或者直接在下方输入</p>
            <div className="cp-suggestions">
              {SUGGESTIONS.map((s, i) => (
                <motion.button
                  key={s.title}
                  type="button"
                  className="cp-suggestion"
                  initial={{ opacity: 0, y: 12 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.4, delay: i * 0.06 }}
                  whileHover={{ y: -2 }}
                  onClick={() => {
                    if (s.action === "sre" && s.fixtureId && onSwitchToSre) {
                      onSwitchToSre(s.fixtureId);
                    } else {
                      onSuggestionClick?.(s.prompt);
                    }
                  }}
                >
                  <span className="cp-suggestion-icon">{s.icon}</span>
                  <span className="cp-suggestion-body">
                    <span className="cp-suggestion-title">{s.title}</span>
                    <span className="cp-suggestion-prompt">{s.prompt}</span>
                  </span>
                </motion.button>
              ))}
            </div>
          </div>
        )}
        {messages.map((msg, i) => (
          <ChatMessage key={i} msg={msg} index={i} onRetry={onRetry} />
        ))}
      </div>
    </div>
  );
}
