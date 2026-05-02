import { motion } from "framer-motion";
import type { AlertPayload } from "../../sre/types";

const SEVERITY_STYLE: Record<AlertPayload["severity"], { label: string; bg: string; fg: string }> = {
  critical: { label: "Critical", bg: "rgba(220,38,38,0.10)", fg: "#b91c1c" },
  high: { label: "High", bg: "rgba(234,88,12,0.10)", fg: "#c2410c" },
  medium: { label: "Medium", bg: "rgba(202,138,4,0.10)", fg: "#a16207" },
  low: { label: "Low", bg: "rgba(8,145,178,0.10)", fg: "#0e7490" },
};

export default function AlertCard({
  alert,
  onClick,
  disabled,
  active,
}: {
  alert: AlertPayload;
  onClick: () => void;
  disabled: boolean;
  active: boolean;
}) {
  const sev = SEVERITY_STYLE[alert.severity];

  return (
    <motion.button
      type="button"
      className={`alert-card ${active ? "active" : ""}`}
      onClick={onClick}
      disabled={disabled}
      whileHover={disabled ? undefined : { y: -2 }}
      whileTap={disabled ? undefined : { scale: 0.98 }}
    >
      <div className="alert-card-head">
        <span className="alert-card-name">{alert.alertName}</span>
        <span className="alert-card-sev" style={{ background: sev.bg, color: sev.fg }}>
          {sev.label}
        </span>
      </div>
      <div className="alert-card-svc">
        <span>{alert.service}</span>
        <span className="alert-card-dot">·</span>
        <span className="alert-card-instance">{alert.instance}</span>
      </div>
      <div className="alert-card-summary">{alert.summary}</div>
      <div className="alert-card-meta">
        <span>{alert.labels.env}</span>
        <span>{alert.labels.team}</span>
        <span>{alert.labels.region}</span>
      </div>

      <style>{`
        .alert-card {
          appearance: none; cursor: pointer; text-align: left;
          border: 1px solid rgba(0,0,0,0.08);
          background: rgba(255,255,255,0.6);
          backdrop-filter: blur(8px);
          border-radius: var(--r-md);
          padding: 14px 16px;
          display: flex; flex-direction: column; gap: 6px;
          transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
          font-family: var(--sans);
        }
        .alert-card:hover:not(:disabled) {
          border-color: rgba(14,165,233,0.4);
          box-shadow: 0 6px 20px rgba(14,165,233,0.12);
          background: rgba(255,255,255,0.8);
        }
        .alert-card:disabled { opacity: 0.5; cursor: not-allowed; }
        .alert-card.active {
          border-color: rgba(14,165,233,0.6);
          box-shadow: 0 8px 24px rgba(14,165,233,0.18);
          background: rgba(255,255,255,0.9);
        }
        .alert-card-head {
          display: flex; align-items: center; justify-content: space-between; gap: 8px;
        }
        .alert-card-name {
          font-weight: 700; font-size: var(--fs-md); color: var(--t1);
        }
        .alert-card-sev {
          font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 999px;
          letter-spacing: 0.02em;
        }
        .alert-card-svc {
          font-size: var(--fs-sm); color: var(--t2);
          display: flex; align-items: center; gap: 6px;
        }
        .alert-card-instance { color: var(--t3); font-family: var(--mono); font-size: 12px; }
        .alert-card-dot { color: var(--t3); }
        .alert-card-summary {
          font-size: var(--fs-sm); color: var(--t2); line-height: 1.5;
        }
        .alert-card-meta {
          display: flex; gap: 6px; flex-wrap: wrap; margin-top: 4px;
        }
        .alert-card-meta span {
          font-size: 11px; color: var(--t3);
          padding: 2px 8px; border-radius: 4px;
          background: rgba(0,0,0,0.04);
        }
      `}</style>
    </motion.button>
  );
}
