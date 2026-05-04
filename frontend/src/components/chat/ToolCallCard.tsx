import { motion } from "framer-motion";
import { Loader2, CheckCircle2, XCircle, Plug, ChevronDown, ChevronRight } from "lucide-react";
import { useState } from "react";
import type { ToolCallState } from "../../hooks/types";
import "./ToolCallCard.css";

/**
 * Inline tool-invocation card. Rendered inside the assistant message bubble
 * for every {@code tool_start} / {@code tool_end} SSE pair. Supports 4 visual
 * states (pending [unused today] / running / success / error) and a 🔌 MCP
 * badge with server name when {@code source === "mcp"}, populated by the
 * backend's {@code McpToolAdapter} via {@code ToolResult.payload}.
 */
export default function ToolCallCard({ toolCall }: { toolCall: ToolCallState }) {
  const [expanded, setExpanded] = useState(false);
  const isMcp = toolCall.source === "mcp";
  const hasArgs = Object.keys(toolCall.args).length > 0;
  const hasResult = !!toolCall.resultPreview;
  const canExpand = hasArgs || hasResult;

  return (
    <motion.div
      className={`tcc tcc-${toolCall.status}`}
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25 }}
    >
      <div
        className="tcc-header"
        onClick={() => canExpand && setExpanded((v) => !v)}
        role={canExpand ? "button" : undefined}
        style={{ cursor: canExpand ? "pointer" : "default" }}
      >
        <span className="tcc-status-icon">
          {toolCall.status === "running" && <Loader2 size={13} className="tcc-spin" />}
          {toolCall.status === "success" && <CheckCircle2 size={13} />}
          {toolCall.status === "error" && <XCircle size={13} />}
        </span>

        {isMcp && (
          <span
            className="tcc-mcp-badge"
            title={`MCP server: ${toolCall.server ?? "?"} · transport: ${toolCall.transport ?? "stdio"}`}
          >
            <Plug size={10} />
            <span className="tcc-mcp-label">MCP</span>
            {toolCall.server && <span className="tcc-server-name">{toolCall.server}</span>}
          </span>
        )}

        <span className="tcc-name">{toolCall.name}</span>

        {toolCall.status === "running" && (
          <span className="tcc-status-text">调用中…</span>
        )}
        {toolCall.status === "success" && toolCall.durationMs !== undefined && toolCall.durationMs > 0 && (
          <span className="tcc-duration">{toolCall.durationMs}ms</span>
        )}
        {toolCall.status === "error" && (
          <span className="tcc-status-text tcc-error-text">失败</span>
        )}

        {canExpand && (
          <span className="tcc-toggle">
            {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
          </span>
        )}
      </div>

      {expanded && (
        <motion.div
          className="tcc-body"
          initial={{ opacity: 0, height: 0 }}
          animate={{ opacity: 1, height: "auto" }}
          transition={{ duration: 0.18 }}
        >
          {hasArgs && (
            <div className="tcc-section">
              <div className="tcc-section-label">入参</div>
              <pre className="tcc-args">{JSON.stringify(toolCall.args, null, 2)}</pre>
            </div>
          )}
          {hasResult && (
            <div className="tcc-section">
              <div className="tcc-section-label">出参摘要</div>
              <pre className="tcc-result">{toolCall.resultPreview}</pre>
            </div>
          )}
        </motion.div>
      )}
    </motion.div>
  );
}
