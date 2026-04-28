import { useState, useCallback } from "react";
import { motion } from "framer-motion";
import { ArrowUp } from "lucide-react";
import "./Composer.css";

export default function Composer({ sending, onSend }: { sending: boolean; onSend: (m: string) => void }) {
  const [input, setInput] = useState("");

  const handleSend = useCallback(() => {
    const t = input.trim();
    if (!t || sending) return;
    onSend(t);
    setInput("");
  }, [input, sending, onSend]);

  const handleKey = useCallback((e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); handleSend(); }
  }, [handleSend]);

  return (
    <motion.div
      className="composer"
      initial={{ y: 20, opacity: 0 }}
      animate={{ y: 0, opacity: 1 }}
      transition={{ duration: 0.5, delay: 0.2 }}
    >
      <div className="composer-inner">
        <textarea
          className="composer-input"
          rows={1}
          placeholder="输入消息…"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKey}
          disabled={sending}
        />
        <motion.button
          type="button"
          className={`composer-send ${input.trim() && !sending ? "ready" : ""}`}
          onClick={handleSend}
          disabled={!input.trim() || sending}
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.92 }}
        >
          <ArrowUp size={18} />
        </motion.button>
      </div>
    </motion.div>
  );
}
