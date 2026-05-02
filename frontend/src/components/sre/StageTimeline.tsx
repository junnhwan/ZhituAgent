import { motion, AnimatePresence } from "framer-motion";
import { Check, Loader2, Minus } from "lucide-react";
import type { SpecialistRunState, SupervisorDecisionState } from "../../sre/types";

export default function StageTimeline({
  specialists,
  decisions,
  finished,
}: {
  specialists: SpecialistRunState[];
  decisions: SupervisorDecisionState[];
  finished: boolean;
}) {
  return (
    <div className="stage-tl">
      {specialists.map((s, idx) => {
        const decision = decisions.find((d) => d.next === s.agentName);
        return (
          <div key={s.agentName} className="stage-tl-row">
            {idx > 0 && (
              <div className="stage-tl-conn">
                <AnimatePresence>
                  {decision && (
                    <motion.div
                      key={decision.round}
                      className="stage-tl-decision"
                      initial={{ opacity: 0, x: -6 }}
                      animate={{ opacity: 1, x: 0 }}
                      exit={{ opacity: 0 }}
                    >
                      <span className="stage-tl-decision-tag">supervisor</span>
                      <span className="stage-tl-decision-reason">{decision.reason}</span>
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
            )}
            <SpecialistRow state={s} />
          </div>
        );
      })}

      {!finished && (
        <div className="stage-tl-pending-hint">
          <span className="stage-tl-dot pulsing" />
          流式接收 supervisor 决策与 specialist 输出…
        </div>
      )}

      <style>{`
        .stage-tl {
          display: flex; flex-direction: column; gap: 0;
        }
        .stage-tl-row {
          display: flex; flex-direction: column;
        }
        .stage-tl-conn {
          padding: 6px 0 6px 26px;
          min-height: 22px;
          display: flex; align-items: center;
          border-left: 2px dashed rgba(14,165,233,0.25);
          margin-left: 11px;
        }
        .stage-tl-decision {
          display: inline-flex; align-items: center; gap: 8px;
          font-size: 12px;
          padding: 4px 10px;
          background: rgba(14,165,233,0.08);
          border: 1px solid rgba(14,165,233,0.18);
          border-radius: 999px;
          color: var(--t2);
        }
        .stage-tl-decision-tag {
          color: rgba(14,165,233,0.95); font-weight: 700; font-size: 10px;
          letter-spacing: 0.04em; text-transform: uppercase;
        }
        .stage-tl-decision-reason {
          color: var(--t2); font-style: italic; max-width: 360px;
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        }
        .stage-tl-pending-hint {
          padding: 12px 0 4px 30px;
          font-size: 12px; color: var(--t3);
          display: flex; align-items: center; gap: 8px;
        }
        .stage-tl-dot {
          width: 8px; height: 8px; border-radius: 50%; background: rgba(14,165,233,0.7);
        }
        .stage-tl-dot.pulsing { animation: pulse 1.4s ease-in-out infinite; }
        @keyframes pulse { 0%, 100% { opacity: 0.3; transform: scale(0.85); } 50% { opacity: 1; transform: scale(1.15); } }
      `}</style>
    </div>
  );
}

function SpecialistRow({ state }: { state: SpecialistRunState }) {
  return (
    <div className={`spec-row spec-${state.status}`}>
      <div className="spec-icon">
        {state.status === "active" && <Loader2 size={14} className="spin" />}
        {state.status === "done" && <Check size={14} />}
        {state.status === "skipped" && <Minus size={14} />}
        {state.status === "pending" && <span className="spec-pending-dot" />}
      </div>
      <div className="spec-body">
        <div className="spec-head">
          <span className="spec-label">{state.label}</span>
          <span className="spec-agent">{state.agentName}</span>
          {state.round !== undefined && <span className="spec-round">round {state.round}</span>}
        </div>
        {state.status === "active" && <div className="spec-status-text">运行中…</div>}
        {state.status === "done" && (
          <div className="spec-status-text">
            完成
            {typeof state.latencyMs === "number" && ` · ${formatLatency(state.latencyMs)}`}
            {state.iterations !== undefined && ` · ${state.iterations} iter`}
            {state.toolsUsed && state.toolsUsed.length > 0 && (
              <span className="spec-tools"> · 调用 {state.toolsUsed.join(", ")}</span>
            )}
          </div>
        )}
        {state.status === "skipped" && (
          <div className="spec-status-text">未执行 — supervisor 判断不需要</div>
        )}
        {state.status === "pending" && <div className="spec-status-text">等待路由</div>}
      </div>

      <style>{`
        .spec-row {
          display: flex; gap: 12px; padding: 12px 14px;
          border: 1px solid rgba(0,0,0,0.06);
          border-radius: var(--r-md);
          background: rgba(255,255,255,0.5);
          transition: all 0.25s ease;
        }
        .spec-row.spec-active {
          border-color: rgba(14,165,233,0.4);
          background: rgba(255,255,255,0.85);
          box-shadow: 0 4px 16px rgba(14,165,233,0.14);
        }
        .spec-row.spec-done {
          border-color: rgba(34,197,94,0.3);
          background: rgba(255,255,255,0.7);
        }
        .spec-row.spec-skipped {
          opacity: 0.55; background: rgba(0,0,0,0.02);
        }
        .spec-icon {
          width: 24px; height: 24px; border-radius: 50%;
          display: flex; align-items: center; justify-content: center;
          flex-shrink: 0;
        }
        .spec-active .spec-icon { background: rgba(14,165,233,0.12); color: rgba(14,165,233,0.95); }
        .spec-done .spec-icon { background: rgba(34,197,94,0.14); color: #15803d; }
        .spec-skipped .spec-icon { background: rgba(0,0,0,0.06); color: var(--t3); }
        .spec-pending-dot {
          width: 8px; height: 8px; border-radius: 50%; background: rgba(0,0,0,0.15);
        }
        .spec-pending .spec-icon { background: rgba(0,0,0,0.04); }
        .spec-body { flex: 1; min-width: 0; }
        .spec-head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
        .spec-label { font-weight: 700; font-size: var(--fs-md); color: var(--t1); }
        .spec-agent {
          font-family: var(--mono); font-size: 11px; color: var(--t3);
          padding: 1px 6px; border-radius: 4px; background: rgba(0,0,0,0.04);
        }
        .spec-round {
          font-size: 11px; color: var(--t3);
          padding: 1px 6px; border-radius: 4px; background: rgba(14,165,233,0.08);
        }
        .spec-status-text { font-size: 12px; color: var(--t2); margin-top: 4px; }
        .spec-tools { color: var(--t3); }
        .spin { animation: spin 1s linear infinite; }
        @keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }
      `}</style>
    </div>
  );
}

function formatLatency(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}
