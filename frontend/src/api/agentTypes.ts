export interface AgentRun {
  id: string;
  planId: string;
  stepId: string;
  workingCopyId: string;
  baseline: string;
  state: "CREATED" | "PREPARING" | "RUNNING" | "FINALIZING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "INTERRUPTED";
  startedAt: string;
  endedAt: string | null;
  reason: string | null;
  candidateId: string | null;
  cancelRequested: boolean;
  retained: boolean;
  modelCalls: number;
  toolCalls: number;
  tokens: number | null;
}

export interface AgentEvent {
  sequence: number;
  runId: string;
  kind: string;
  summary: string;
  at: string;
}

export interface AgentCandidate {
  id: string;
  runId: string;
  baseline: string;
  expectedTree: string;
  sha256: string;
  changedPaths: string[];
  diffBytes: number;
  testEvidence: string;
  createdAt: string;
}

export interface Delivery {
  candidateId: string;
  state: "INTENT" | "APPLIED" | "COMMIT_READY" | "PUBLISHED" | "COMPLETED" | "DELIVERY_BLOCKED" | "RECOVERY_REQUIRED";
  revision: string | null;
  reason: string | null;
}
