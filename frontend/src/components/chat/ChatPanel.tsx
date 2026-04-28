import { MessageSquare } from "lucide-react";
import ChatMessage from "./ChatMessage";
import type { MessageState } from "../../hooks/types";
import { useAutoScroll } from "../../hooks/useAutoScroll";
import "./ChatPanel.css";

export default function ChatPanel({ messages }: { messages: MessageState[] }) {
  const scrollRef = useAutoScroll(messages);

  return (
    <div className="cp" ref={scrollRef}>
      {messages.length === 0 && (
        <div className="cp-empty">
          <div className="cp-empty-icon">
            <MessageSquare size={28} />
          </div>
          <p className="cp-empty-title">开始对话</p>
          <p className="cp-empty-sub">向 Agent 发送消息，体验 SSE 流式输出</p>
        </div>
      )}
      {messages.map((msg, i) => (
        <ChatMessage key={i} msg={msg} index={i} />
      ))}
    </div>
  );
}
