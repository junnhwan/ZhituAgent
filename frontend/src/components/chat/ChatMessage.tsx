import { motion } from "framer-motion";
import { Bot, User } from "lucide-react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { MessageState } from "../../hooks/types";
import StreamingCursor from "./StreamingCursor";
import "./ChatMessage.css";

export default function ChatMessage({ msg, index }: { msg: MessageState; index: number }) {
  const isUser = msg.role === "user";

  return (
    <motion.div
      className={`cm ${msg.role}`}
      initial={{ opacity: 0, y: 16, scale: 0.97 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ duration: 0.35, delay: index * 0.03 }}
    >
      <div className={`cm-avatar ${msg.role}`}>
        {isUser ? <User size={16} /> : <Bot size={16} />}
      </div>

      <div className="cm-bubble-wrap">
        <div className="cm-bubble">
          {isUser ? (
            <span className="cm-text">{msg.content}</span>
          ) : (
            <div className="cm-text cm-md">
              <Markdown remarkPlugins={[remarkGfm]}>{msg.content}</Markdown>
            </div>
          )}
          {msg.isStreaming && <StreamingCursor />}
        </div>
        <span className="cm-role">{isUser ? "你" : "Agent"}</span>
      </div>
    </motion.div>
  );
}
