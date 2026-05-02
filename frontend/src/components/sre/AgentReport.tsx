import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { AlertReport } from "../../api/alert";

export default function AgentReport({ report }: { report: AlertReport }) {
  const [showDecisions, setShowDecisions] = useState(false);
  const [showExecutions, setShowExecutions] = useState(false);

  return (
    <div className="agent-report">
      <div className="ar-summary">
        <div className="ar-stat">
          <span className="ar-stat-label">Agent trail</span>
          <span className="ar-stat-value">{report.agentTrail.join(" → ")}</span>
        </div>
        <div className="ar-stat-row">
          <div className="ar-stat">
            <span className="ar-stat-label">Rounds</span>
            <span className="ar-stat-value">{report.rounds}</span>
          </div>
          <div className="ar-stat">
            <span className="ar-stat-label">Latency</span>
            <span className="ar-stat-value">{(report.latencyMs / 1000).toFixed(1)}s</span>
          </div>
          <div className="ar-stat">
            <span className="ar-stat-label">Trace</span>
            <span className="ar-stat-value mono">#{report.traceId.slice(0, 8)}</span>
          </div>
          {report.reachedMaxRounds && <span className="ar-warn">达到 maxRounds 上限</span>}
        </div>
      </div>

      <div className="ar-md">
        <Markdown remarkPlugins={[remarkGfm]}>{report.reportMarkdown}</Markdown>
      </div>

      <button type="button" className="ar-fold" onClick={() => setShowDecisions((s) => !s)}>
        {showDecisions ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        Supervisor 决策({report.supervisorDecisions.length})
      </button>
      {showDecisions && (
        <table className="ar-table">
          <thead>
            <tr>
              <th>Round</th>
              <th>Next</th>
              <th>Reason</th>
              <th>Latency</th>
            </tr>
          </thead>
          <tbody>
            {report.supervisorDecisions.map((d) => (
              <tr key={d.round}>
                <td>{d.round}</td>
                <td className="mono">{d.next}</td>
                <td className="ar-reason">{d.reason}</td>
                <td>{d.latencyMs}ms</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <button type="button" className="ar-fold" onClick={() => setShowExecutions((s) => !s)}>
        {showExecutions ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        Specialist 执行明细({report.executions.length})
      </button>
      {showExecutions && (
        <table className="ar-table">
          <thead>
            <tr>
              <th>Agent</th>
              <th>Iter</th>
              <th>Tools</th>
              <th>Latency</th>
            </tr>
          </thead>
          <tbody>
            {report.executions.map((e, i) => (
              <tr key={`${e.agentName}-${i}`}>
                <td className="mono">{e.agentName}</td>
                <td>{e.iterations}</td>
                <td className="mono">{e.toolsUsed.length === 0 ? "—" : e.toolsUsed.join(", ")}</td>
                <td>{e.latencyMs}ms</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <style>{`
        .agent-report {
          display: flex; flex-direction: column; gap: 16px;
        }
        .ar-summary {
          display: flex; flex-direction: column; gap: 8px;
          padding: 14px 16px;
          border: 1px solid rgba(0,0,0,0.08);
          border-radius: var(--r-md);
          background: rgba(255,255,255,0.5);
        }
        .ar-stat-row { display: flex; gap: 24px; flex-wrap: wrap; align-items: center; }
        .ar-stat { display: flex; flex-direction: column; gap: 2px; }
        .ar-stat-label {
          font-size: 11px; color: var(--t3); text-transform: uppercase; letter-spacing: 0.04em;
        }
        .ar-stat-value { font-size: var(--fs-sm); color: var(--t1); font-weight: 600; }
        .ar-stat-value.mono { font-family: var(--mono); }
        .ar-warn {
          font-size: 11px; padding: 2px 8px; border-radius: 999px;
          background: rgba(234,88,12,0.12); color: #c2410c;
        }
        .ar-md {
          padding: 18px 20px;
          border: 1px solid rgba(0,0,0,0.08);
          border-radius: var(--r-md);
          background: rgba(255,255,255,0.7);
          font-size: var(--fs-base); line-height: 1.6;
        }
        .ar-md h1, .ar-md h2, .ar-md h3 { margin-top: 14px; margin-bottom: 8px; font-weight: 700; }
        .ar-md h1 { font-size: 1.3rem; }
        .ar-md h2 { font-size: 1.15rem; }
        .ar-md h3 { font-size: 1.05rem; }
        .ar-md p { margin: 0 0 10px; }
        .ar-md ul, .ar-md ol { margin: 0 0 10px; padding-left: 24px; }
        .ar-md li { margin: 4px 0; }
        .ar-md code {
          font-family: var(--mono); font-size: 0.9em;
          background: rgba(0,0,0,0.05); padding: 1px 6px; border-radius: 4px;
        }
        .ar-md strong { font-weight: 700; }
        .ar-fold {
          appearance: none; border: none; cursor: pointer;
          display: inline-flex; align-items: center; gap: 6px;
          font-size: var(--fs-sm); color: var(--t2); padding: 6px 0;
          background: transparent;
          font-family: var(--sans);
        }
        .ar-fold:hover { color: var(--t1); }
        .ar-table {
          width: 100%; border-collapse: collapse;
          font-size: var(--fs-sm);
        }
        .ar-table th, .ar-table td {
          padding: 6px 10px; text-align: left;
          border-bottom: 1px solid rgba(0,0,0,0.06);
        }
        .ar-table th {
          font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em;
          color: var(--t3); font-weight: 600;
        }
        .ar-table td.mono, .ar-table .mono { font-family: var(--mono); font-size: 12px; }
        .ar-reason { color: var(--t2); font-style: italic; max-width: 320px; }
      `}</style>
    </div>
  );
}
