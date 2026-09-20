/** 계약 타입 — fixture와 실 API가 같은 모양을 쓴다. */

export type Category = {
  code: string;
  name: string;
  description: string | null;
  sortOrder: number;
  productCount: number;
  /** null이면 대분류, 값이 있으면 그 코드를 부모로 둔 중분류다(V56). */
  parentCode: string | null;
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
export type Unit = { x: string; y: string };
export type BarItem = { label: string; value: number; display?: string; color?: string };
export type Span = { name: string; start: number; ms: number; color?: string; note?: string };

/* ---------- 실험별 계약 ---------- */

export type CacheFixture = {
  unit: Unit;
  series: Points[];
  rows: { sizeKB: number; noneMs: number; lz4Ms: number; snappyMs: number; bytesReducedPct: number; cpuMs: number }[];
  conclusion: Conclusion & { thresholdKB: number };
  _harness?: string;
  _method?: string;
};

export type FreshnessFixture = {
  unit: Unit;
  tradeoff: Points[];
  policies: { waitMs: number; label: string; freshnessPct: number; p95Ms: number; timeoutPct: number }[];
  breakdown: { label: string; value: number }[];
  conclusion: Conclusion;
  _harness?: string;
};

export type ConsistencyFixture = {
  replay: { contexts: number; matched: number; matchPct: number };
  causes: { label: string; value: number; sharePct: number; color: string }[];
  examples: { context: string; offline: number; online: number; cause: string }[];
  conclusion: Conclusion;
  _harness?: string;
};

export type OverloadFixture = {
  unit: Unit;
  slo: { p95Ms: number; label: string };
  series: Points[];
  rows: {
    rps: number;
    policy: string;
    p95Ms: number;
    p99Ms: number;
    timeoutPct: number;
    fallbackPct: number;
    coveragePct: number;
  }[];
  conclusion: Conclusion;
  _harness?: string;
};

export type ConstraintsFixture = {
  modes: { mode: string; addedMs: number; violationPct: number; staleWindowMs: number; factsChanged: number }[];
  tradeoff: Points[];
  boundary: { recommendation: string; statement: string; openQuestion: string };
  conclusion: Conclusion;
  _harness?: string;
};

export type BudgetFixture = {
  budgetMs: number;
  modes: {
    mode: string;
    rankMs: number;
    arMs: number;
    contextMs: number;
    constraintMs: number;
    postMs: number;
    e2eMs: number;
    throughputRps: number;
  }[];
  share: BarItem[];
  conclusion: Conclusion;
  _harness?: string;
};

export type TraceFixture = {
  requestId: string;
  userId: string;
  at: string;
  totalMs: number;
  spans: Span[];
  stale: {
    lastEventAgeMs: number;
    reason: string;
    affectedFeature: string;
    offlineValue: number;
    onlineValue: number;
  };
  constraints: { itemId: string; check: string; state: string; checkedAt: string }[];
  output: { rows: number; items: number; droppedByConstraint: number; source: string };
  inputSnapshot: {
    lastViewedItem: string;
    recentCategories: string[];
    clickCount1h: number;
    contextVersion: string;
  };
};
