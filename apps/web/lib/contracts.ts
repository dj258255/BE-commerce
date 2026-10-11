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
  /** 카드에 그릴 값 — 없으면 소비자가 상품 API 를 한 번 더 불러야 해서 조립과 화면이 갈라진다. */
  imageUrl: string | null;
  inStock: boolean;
  /**
   * 모델이 낸 관련도 점수. **null 일 수 있다** — 모델 스텁은 순위만 내지 점수를 내지 않는다.
   * 없는 점수를 화면이 지어내면 "관련도 0.91"처럼 보이지만 그 숫자의 근거가 없다.
   */
  score: number | null;
  reason: string;
};

export type HomepageRow = {
  id: string;
  title: string;
  strategy: string;
  items: RowItem[];
};

/**
 * 조립이 무엇을 했는가 — 실 API만 준다(목 계약에는 없던 필드).
 * **버린 것을 밝히지 않으면 "왜 이 화면인가"를 아무도 복원할 수 없다.**
 */
export type AssemblyStats = {
  candidates: number;
  unmatched: number;
  outOfStock: number;
  duplicates: number;
  cappedOut: number;
  distinctCategories: number;
  /** 재고 확인 계측(X3, #317) — 방식별 대가. 없으면 "무엇을 얼마에 샀는가"를 복원할 수 없다. */
  stockLookups?: number;
  stockLookupMs?: number;
  stockRemoved?: number;
};

export type Homepage = {
  _mock?: boolean;
  _source?: string;
  userId: string;
  generatedAt: string;
  /** 실 API는 MODEL | FALLBACK 두 가지다(추천을 못 받으면 카탈로그만으로 조립한다). */
  source: 'MODEL' | 'FALLBACK' | 'FALLBACK_POPULARITY' | 'CACHE';
  fallbackReason: string | null;
  /** null 일 수 있다 — 아직 계기에서 안 나오는 경우가 있다(0 과 "모른다"는 다르다). */
  contextStalenessMs: number | null;
  latency: { contextMs: number; inferenceMs: number; constraintMs: number; totalMs: number };
  rows: HomepageRow[];
  stats?: AssemblyStats;
};

/* ---------- 숏폼 피드(R26) ---------- */

/** 숏폼 영상에 연결된 상품 요약(R25) — 피드 항목 아래에 보여 준다. */
export type ShortsFeedProduct = { productId: number; name: string; price: number };

/**
 * 피드 항목 — READY인 영상만(변환 완료). `masterPlaylistUrl`·`thumbnailUrl`(R26 재생)은
 * `GET /api/v1/shorts/{id}/media/**`(공개)로 바로 재생·표시할 수 있는 이 사이트 기준 상대
 * 경로다 — `next.config.ts`의 `/api/:path*` rewrite가 Spring으로 보낸다.
 */
export type ShortsFeedItem = {
  id: number;
  durationSeconds: number;
  width: number;
  height: number;
  createdAt: string;
  masterPlaylistUrl: string;
  thumbnailUrl: string;
  products: ShortsFeedProduct[];
};

/**
 * 커서 기반 한 쪽 — `nextCursor`가 null이면 마지막 쪽이다. `fallback`(R29)이 true면 개인화
 * 점수 계산이 실패·지연돼 `items`가 최신순 READY 순서 그대로라는 뜻이다(조회 자체는 항상 성공).
 */
export type ShortsFeedPage = { items: ShortsFeedItem[]; nextCursor: number | null; hasNext: boolean; fallback: boolean };

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
