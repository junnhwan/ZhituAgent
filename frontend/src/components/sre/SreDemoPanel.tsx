import { useCallback, useState, useRef, useEffect } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { ALERT_FIXTURES, SPECIALIST_LABELS } from "../../sre/fixtures";
import type {
  AlertPayload,
  SpecialistRunState,
  SupervisorDecisionState,
} from "../../sre/types";
import { streamAlert, type AlertReport, type AlertStageEvent } from "../../api/alert";
import AlertCard from "./AlertCard";
import StageTimeline from "./StageTimeline";
import AgentReport from "./AgentReport";

const SPECIALIST_ORDER = ["AlertTriageAgent", "LogQueryAgent", "ReportAgent"];

type Props = {
  autoStartAlertId?: string | null;
  onAutoStartConsumed?: () => void;
};

function initialSpecialists(): SpecialistRunState[] {
  return SPECIALIST_ORDER.map((agentName) => ({
    agentName,
    label: SPECIALIST_LABELS[agentName] ?? agentName,
    status: "pending",
  }));
}

export default function SreDemoPanel({ autoStartAlertId, onAutoStartConsumed }: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(false);
  const [specialists, setSpecialists] = useState<SpecialistRunState[]>(initialSpecialists);
  const [decisions, setDecisions] = useState<SupervisorDecisionState[]>([]);
  const [report, setReport] = useState<AlertReport | null>(null);
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  const handleStart = useCallback((alert: AlertPayload) => {
    if (streaming) return;
    abortRef.current?.abort();

    setSelectedId(alert.alertId);
    setStreaming(true);
    setSpecialists(initialSpecialists());
    setDecisions([]);
    setReport(null);
    setError(null);

    abortRef.current = streamAlert(alert, {
      onStart: () => {
        // sessionId/traceId noted but not surfaced until report arrives
      },
      onStage: (event: AlertStageEvent) => {
        if (event.phase === "supervisor-routing") {
          // Reason fills in once final report lands; placeholder keeps the row visible.
          setDecisions((prev) => {
            if (prev.some((d) => d.round === event.detail.round)) return prev;
            return [...prev, { round: event.detail.round, next: "", reason: "supervisor 决策中…" }];
          });
        } else if (event.phase === "agent" || event.phase === "final-writing") {
          const target = event.detail.agentName;
          if (!target) return;
          setSpecialists((prev) =>
            prev.map((s) =>
              s.agentName === target
                ? { ...s, status: "active", round: event.detail.round }
                : s,
            ),
          );
        }
      },
      onReport: (rep: AlertReport) => {
        setReport(rep);
        // Reconcile: anything not in agentTrail is "skipped"; trailed agents → "done" with execution stats.
        setSpecialists((prev) =>
          prev.map((s) => {
            const trailed = rep.agentTrail.includes(s.agentName);
            const exec = rep.executions.find((e) => e.agentName === s.agentName);
            if (!trailed) return { ...s, status: "skipped" };
            return {
              ...s,
              status: "done",
              iterations: exec?.iterations,
              toolsUsed: exec?.toolsUsed,
              latencyMs: exec?.latencyMs,
            };
          }),
        );
        setDecisions(
          rep.supervisorDecisions.map((d) => ({
            round: d.round,
            next: d.next,
            reason: d.reason,
            latencyMs: d.latencyMs,
          })),
        );
      },
      onError: (code, message) => {
        setError({ code, message });
        setStreaming(false);
      },
      onComplete: () => {
        setStreaming(false);
        abortRef.current = null;
      },
    });
  }, [streaming]);

  const handleReset = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setSelectedId(null);
    setStreaming(false);
    setSpecialists(initialSpecialists());
    setDecisions([]);
    setReport(null);
    setError(null);
  }, []);

  useEffect(() => {
    if (!autoStartAlertId || streaming) return;
    const fixture = ALERT_FIXTURES.find((a) => a.alertId === autoStartAlertId);
    if (!fixture) return;
    handleStart(fixture);
    onAutoStartConsumed?.();
  }, [autoStartAlertId, streaming, handleStart, onAutoStartConsumed]);

  return (
    <div className="sre-panel">
      <div className="sre-intro">
        <h2 className="sre-intro-title">SRE 告警分析 Multi-Agent Demo</h2>
        <p className="sre-intro-sub">
          点击告警卡片触发 Supervisor + 3 Specialist 多智能体编排,实时观察路由决策与日志查询过程。
        </p>
      </div>

      <div className="sre-cards">
        {ALERT_FIXTURES.map((alert) => (
          <AlertCard
            key={alert.alertId}
            alert={alert}
            onClick={() => handleStart(alert)}
            disabled={streaming}
            active={selectedId === alert.alertId}
          />
        ))}
      </div>

      <AnimatePresence>
        {selectedId && (
          <motion.div
            className="sre-runspace"
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ duration: 0.25 }}
          >
            <div className="sre-runspace-head">
              <span className="sre-runspace-title">编排 timeline</span>
              <button type="button" className="sre-reset" onClick={handleReset}>
                重置
              </button>
            </div>

            <StageTimeline
              specialists={specialists}
              decisions={decisions}
              finished={!streaming && report !== null}
            />

            {error && (
              <div className="sre-error">
                <span className="sre-error-code">{error.code}</span>
                <span className="sre-error-msg">{error.message}</span>
              </div>
            )}

            {report && (
              <motion.div
                className="sre-report-wrap"
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3, delay: 0.1 }}
              >
                <div className="sre-report-head">最终报告</div>
                <AgentReport report={report} />
              </motion.div>
            )}
          </motion.div>
        )}
      </AnimatePresence>

      <style>{`
        .sre-panel {
          padding: 24px 32px 48px;
          display: flex; flex-direction: column; gap: 24px;
          overflow-y: auto; height: 100%;
        }
        .sre-intro-title {
          font-size: 1.4rem; font-weight: 700; color: var(--t1);
          margin: 0 0 4px;
        }
        .sre-intro-sub {
          font-size: var(--fs-sm); color: var(--t2); margin: 0;
        }
        .sre-cards {
          display: grid;
          grid-template-columns: repeat(2, minmax(260px, 1fr));
          gap: 12px;
        }
        @media (max-width: 1100px) {
          .sre-cards { grid-template-columns: 1fr; }
        }
        .sre-runspace {
          display: flex; flex-direction: column; gap: 16px;
          padding: 20px;
          border: 1px solid rgba(0,0,0,0.08);
          border-radius: var(--r-md);
          background: rgba(255,255,255,0.45);
          backdrop-filter: blur(10px);
        }
        .sre-runspace-head {
          display: flex; align-items: center; justify-content: space-between;
        }
        .sre-runspace-title {
          font-weight: 700; font-size: var(--fs-md); color: var(--t1);
        }
        .sre-reset {
          appearance: none; border: 1px solid rgba(0,0,0,0.1);
          background: rgba(255,255,255,0.6); cursor: pointer;
          padding: 4px 12px; border-radius: var(--r-sm);
          font-size: 12px; color: var(--t2); font-family: var(--sans);
        }
        .sre-reset:hover { background: rgba(255,255,255,0.85); color: var(--t1); }
        .sre-error {
          padding: 10px 14px;
          border: 1px solid rgba(220,38,38,0.25);
          background: rgba(220,38,38,0.06);
          border-radius: var(--r-sm);
          display: flex; align-items: center; gap: 10px;
          font-size: var(--fs-sm);
        }
        .sre-error-code {
          font-family: var(--mono); font-size: 11px;
          color: #b91c1c; padding: 2px 8px; border-radius: 4px;
          background: rgba(220,38,38,0.10);
        }
        .sre-error-msg { color: var(--t2); }
        .sre-report-wrap {
          display: flex; flex-direction: column; gap: 12px;
          padding-top: 8px; border-top: 1px solid rgba(0,0,0,0.06);
        }
        .sre-report-head {
          font-weight: 700; font-size: var(--fs-md); color: var(--t1);
        }
      `}</style>
    </div>
  );
}
