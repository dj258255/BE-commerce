/** 계약 타입 — fixture와 실 API가 같은 모양을 쓴다. */

export type Category = {
  code: string;
  name: string;
  description: string | null;
  sortOrder: number;
  productCount: number;
};

export type RowItem = {
  itemId: string;
  name: string;
  price: number;
  score: number;
  reason: string;
};

export type HomepageRow = {
  id: string;
  title: string;
  strategy: string;
  items: RowItem[];
};

export type Homepage = {
  _mock?: boolean;
  _source?: string;
  userId: string;
  generatedAt: string;
  source: 'MODEL' | 'FALLBACK_POPULARITY' | 'CACHE';
  fallbackReason: string | null;
  contextStalenessMs: number;
  latency: { contextMs: number; inferenceMs: number; constraintMs: number; totalMs: number };
  rows: HomepageRow[];
};

export type ExperimentStatus = 'idea' | 'todo' | 'running' | 'done';

export type ExperimentSummary = {
  id: string;
  title: string;
  milestone: string;
  issue: number;
  question: string;
  status: ExperimentStatus;
  href: string | null;
  summary: string | null;
};

export type ExperimentsFile = { _mock?: boolean; experiments: ExperimentSummary[] };

export type Conclusion = { statement: string; tradeoff: string; openQuestion: string };

export type Points = { name: string; cls?: string; points: [number, number][] };
