import type { AlertPayload } from "./types";

export const ALERT_FIXTURES: AlertPayload[] = [
  {
    alertId: "alert-001",
    severity: "high",
    alertName: "HighCPUUsage",
    service: "order-service",
    instance: "order-service-prod-3",
    firedAt: "2026-05-01T14:32:00+08:00",
    summary: "CPU usage > 90% for 10 minutes",
    labels: { env: "prod", team: "trade", region: "cn-hangzhou" },
    annotations: {
      description:
        "Order service instance order-service-prod-3 sustained CPU at 92-95% for the past 10 minutes; QPS spiked from baseline ~300 to ~1200 around 14:22 (deploy window).",
      runbookHint: "HighCPUUsage",
    },
  },
  {
    alertId: "alert-002",
    severity: "high",
    alertName: "HighMemoryUsage",
    service: "payment-service",
    instance: "payment-service-prod-2",
    firedAt: "2026-05-01T11:18:00+08:00",
    summary: "Heap usage > 85% with old-gen growth for 15 minutes",
    labels: { env: "prod", team: "trade", region: "cn-hangzhou" },
    annotations: {
      description:
        "Payment service instance payment-service-prod-2 memory steadily climbed from 60% to 87% over 15 minutes, old-generation occupancy at 92%; minor GC frequency 180/min (baseline ~30/min). No traffic spike observed.",
      runbookHint: "HighMemoryUsage",
    },
  },
  {
    alertId: "alert-003",
    severity: "critical",
    alertName: "DBConnectionPoolExhausted",
    service: "user-service",
    instance: "user-service-prod-1",
    firedAt: "2026-05-01T16:05:00+08:00",
    summary: "HikariCP active connections at max (100/100) with pending threads > 50",
    labels: { env: "prod", team: "account", region: "cn-hangzhou" },
    annotations: {
      description:
        "User service HikariCP pool saturated: active=100/max=100, pendingThreads=53, average wait > 2s. Slow SQL counter jumped 42 in last 5 minutes.",
      runbookHint: "DBConnectionPoolExhausted",
    },
  },
  {
    alertId: "alert-004",
    severity: "medium",
    alertName: "SlowResponse",
    service: "order-service",
    instance: "order-service-prod-1",
    firedAt: "2026-05-01T20:42:00+08:00",
    summary: "p99 response time > 2s for the last 5 minutes",
    labels: { env: "prod", team: "trade", region: "cn-hangzhou" },
    annotations: {
      description:
        "Order service p99 latency rose from 480ms baseline to 2150ms over the last 5 minutes; downstream payment-service call accounts for ~1700ms. Overall QPS unchanged.",
      runbookHint: "SlowResponse",
    },
  },
];

export const SPECIALIST_LABELS: Record<string, string> = {
  AlertTriageAgent: "告警分析",
  LogQueryAgent: "日志查询",
  ReportAgent: "报告生成",
};
