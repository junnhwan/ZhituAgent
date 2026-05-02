export interface AlertPayload {
  alertId: string;
  severity: "low" | "medium" | "high" | "critical";
  alertName: string;
  service: string;
  instance: string;
  firedAt: string;
  summary: string;
  labels: Record<string, string>;
  annotations: Record<string, string>;
}

export interface SpecialistRunState {
  agentName: string;
  label: string;
  status: "pending" | "active" | "done" | "skipped";
  round?: number;
  iterations?: number;
  toolsUsed?: string[];
  latencyMs?: number;
}

export interface SupervisorDecisionState {
  round: number;
  next: string;
  reason: string;
  latencyMs?: number;
}
