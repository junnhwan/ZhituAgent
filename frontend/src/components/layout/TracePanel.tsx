import { motion } from "framer-motion";
import { Activity, Database, Wrench, CircleDot } from "lucide-react";
import type { TraceDisplay } from "../../hooks/useStreamingChat";
import "./TracePanel.css";

export default function TracePanel({ trace }: { trace: TraceDisplay }) {
  return (
    <div>
      <div className="aside-title">Run Trace</div>

      <div className="aside-metrics">
        <Metric icon={<Activity size={15} />} label="路径" value={trace.path} />
        <Metric icon={<Database size={15} />} label="RAG 命中" value={trace.retrievalHit ? "是" : "否"} positive={trace.retrievalHit} />
        <Metric icon={<Wrench size={15} />} label="工具调用" value={trace.toolUsed ? "是" : "否"} positive={trace.toolUsed} />
      </div>

      {/* Status bar */}
      <div className="aside-status">
        <div className="aside-status-row">
          <CircleDot size={14} className={`aside-status-icon ${trace.status}`} />
          <span className="aside-status-label">状态</span>
        </div>
        <motion.span
          key={trace.status}
          initial={{ opacity: 0, y: 4 }}
          animate={{ opacity: 1, y: 0 }}
          className={`aside-status-value status-${trace.status}`}
        >
          {statusLabel(trace.status)}
        </motion.span>
      </div>

      {/* Decorative bar */}
      <div className="aside-bar-track">
        <motion.div
          className="aside-bar-fill"
          initial={{ width: 0 }}
          animate={{ width: trace.status === "streaming" ? "60%" : trace.status === "complete" ? "100%" : "0%" }}
          transition={{ duration: 0.8, ease: "easeOut" }}
        />
      </div>
    </div>
  );
}

function Metric({ icon, label, value, positive }: {
  icon: React.ReactNode; label: string; value: string; positive?: boolean;
}) {
  return (
    <div className="aside-metric">
      <div className="aside-metric-head">
        {icon}
        <span className="aside-metric-label">{label}</span>
      </div>
      <motion.span
        key={value}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className={`aside-metric-val ${positive ? "positive" : ""}`}
      >
        {value}
      </motion.span>
    </div>
  );
}

function statusLabel(s: string) {
  switch (s) {
    case "idle": return "待命";
    case "streaming": return "生成中…";
    case "complete": return "完成";
    case "error": return "出错";
    default: return s;
  }
}
