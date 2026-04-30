import { motion, AnimatePresence } from "framer-motion";
import { Shield, Check, X } from "lucide-react";
import { useState } from "react";
import type { PendingToolCall } from "../../types/events";
import "./HitlConfirmPanel.css";

interface HitlConfirmPanelProps {
  pending: PendingToolCall | null;
  onApprove: (pendingId: string) => Promise<void>;
  onDeny: (pendingId: string) => Promise<void>;
}

export default function HitlConfirmPanel({ pending, onApprove, onDeny }: HitlConfirmPanelProps) {
  const [busy, setBusy] = useState<"approve" | "deny" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handle = async (action: "approve" | "deny") => {
    if (!pending || busy) return;
    setBusy(action);
    setError(null);
    try {
      if (action === "approve") {
        await onApprove(pending.pendingId);
      } else {
        await onDeny(pending.pendingId);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败,请重试");
    } finally {
      setBusy(null);
    }
  };

  return (
    <AnimatePresence>
      {pending && (
        <motion.div
          className="hitl-overlay"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
        >
          <motion.div
            className="hitl-panel"
            initial={{ y: 20, scale: 0.95, opacity: 0 }}
            animate={{ y: 0, scale: 1, opacity: 1 }}
            exit={{ y: 20, scale: 0.95, opacity: 0 }}
            transition={{ duration: 0.25, ease: "easeOut" }}
          >
            <div className="hitl-header">
              <Shield size={18} className="hitl-shield" />
              <div className="hitl-title">需要审批</div>
              <div className="hitl-pending-id">{pending.pendingId.slice(0, 8)}</div>
            </div>

            <div className="hitl-body">
              <div className="hitl-tool-name">
                工具：<span className="hitl-tool-name-val">{pending.toolName}</span>
              </div>
              <div className="hitl-section-title">参数</div>
              <pre className="hitl-args">{JSON.stringify(pending.arguments, null, 2)}</pre>
              <div className="hitl-hint">
                此工具会修改知识库或外部系统状态。批准后会自动重新发起本次请求并真正执行。
              </div>
              {error && <div className="hitl-error">{error}</div>}
            </div>

            <div className="hitl-actions">
              <button
                type="button"
                className="hitl-btn hitl-deny"
                onClick={() => handle("deny")}
                disabled={!!busy}
              >
                <X size={14} /> {busy === "deny" ? "拒绝中…" : "拒绝"}
              </button>
              <button
                type="button"
                className="hitl-btn hitl-approve"
                onClick={() => handle("approve")}
                disabled={!!busy}
              >
                <Check size={14} /> {busy === "approve" ? "批准中…" : "批准并执行"}
              </button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
