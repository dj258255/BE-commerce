import { Bars, Timeline } from './Charts';
import { DataTable } from './DataTable';
import { ChartLegend, LineChart } from './LineChart';
import { Badge, Kpi } from './ui';
import type {
  BudgetFixture,
  CacheFixture,
  Conclusion,
  ConsistencyFixture,
  ConstraintsFixture,
  FreshnessFixture,
  OverloadFixture,
  TraceFixture,
} from '@/lib/contracts';

const n0 = (v: number) => v.toLocaleString('ko-KR');
const n1 = (v: number) => v.toLocaleString('ko-KR', { maximumFractionDigits: 1 });
const n2 = (v: number) => v.toLocaleString('ko-KR', { maximumFractionDigits: 2 });
const pct = (v: number, d = 1) => `${v.toLocaleString('ko-KR', { maximumFractionDigits: d })}%`;
const ms = (v: number) => `${v < 10 ? n1(v) : n0(v)}ms`;

/** 결론·트레이드오프·미해결을 한 블록으로. 미해결을 지우지 않는다. */
function ConclusionBlock({ c, badgeKind = 'warn', badgeLabel = '미해결' }: { c: Conclusion; badgeKind?: 'warn' | 'info'; badgeLabel?: string }) {
  return (
    <div>
      <p style={{ margin: '0 0 10px' }}>{c.statement}</p>
      <p style={{ margin: '0 0 10px', color: 'var(--sub)' }}>
        <b>트레이드오프</b> — {c.tradeoff}
      </p>
      <Badge kind={badgeKind}>
        {badgeLabel} — {c.openQuestion}
      </Badge>
    </div>
  );
}

function Harness({ text }: { text?: string }) {
  if (!text) return null;
  return (
    <p className="muted" style={{ marginTop: 18, fontSize: 12.5 }}>
      하네스: {text}
    </p>
  );
}

/* ---------------- E6. 캐시 압축 임계값 ---------------- */

export function CacheScreen({ d }: { d: CacheFixture }) {
  const last = d.rows[d.rows.length - 1];
  const first = d.rows[0];
  return (
    <>
      <div className="grid g3" style={{ marginBottom: 16 }}>
        <Kpi label="권장 임계값" value={`${d.conclusion.thresholdKB}KB`} desc="이상부터 압축" kind="good" />
        <Kpi label={`${last.sizeKB}KB에서 GET`} value={ms(last.lz4Ms)} desc={`무압축 ${ms(last.noneMs)} 대비`} kind="good" />
        <Kpi label={`${first.sizeKB}KB에서 GET`} value={ms(first.lz4Ms)} desc={`무압축 ${ms(first.noneMs)} 보다 느림`} kind="bad" />
      </div>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>Redis GET p95 — 값 크기 구간별</h2>
        <div className="hint">
          x = 값 크기({d.unit.x}), y = GET p95({d.unit.y}). 작은 값에서는 압축이 오히려 느리다.
        </div>
        <LineChart series={d.series} xLabel="value size (KB)" xTicks={[1, 50, 350, 700]} y0 width={760} height={260} />
        <ChartLegend series={d.series} />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>원자료</h2>
        <div className="hint">압축률은 올라가지만 CPU도 함께 쓴다. 임계값 아래에서는 이 교환이 손해다.</div>
        <DataTable
          columns={[
            { key: 'sizeKB', label: '크기(KB)', num: true },
            { key: 'noneMs', label: '무압축 p95(ms)', num: true, fmt: (v) => n2(v as number) },
            { key: 'lz4Ms', label: 'LZ4 p95(ms)', num: true, fmt: (v) => n2(v as number) },
            { key: 'snappyMs', label: 'Snappy p95(ms)', num: true, fmt: (v) => n2(v as number) },
            { key: 'bytesReducedPct', label: '전송량 감소', num: true, fmt: (v) => pct(v as number) },
            { key: 'cpuMs', label: '압축 CPU(ms)', num: true, fmt: (v) => n2(v as number) },
          ]}
          rows={d.rows}
        />
      </section>
      <section className="panel">
        <h2>결론과 트레이드오프</h2>
        <ConclusionBlock c={d.conclusion} />
      </section>
      <Harness text={d._harness} />
    </>
  );
}

/* ---------------- E1. 신선도 vs 지연 ---------------- */

export function FreshnessScreen({ d }: { d: FreshnessFixture }) {
  const p = d.policies;
  const worst = p[p.length - 1];
  return (
    <>
      <div className="grid g3" style={{ marginBottom: 16 }}>
        <Kpi label="즉시 응답 반영률" value={pct(p[0].freshnessPct)} desc={`p95 ${ms(p[0].p95Ms)}`} />
        <Kpi label={`${p[2].label}`} value={pct(p[2].freshnessPct)} desc={`p95 ${ms(p[2].p95Ms)} (+${n0(p[2].p95Ms - p[0].p95Ms)}ms)`} kind="warn" />
        <Kpi label={worst.label} value={pct(worst.freshnessPct)} desc={`p95 ${ms(worst.p95Ms)} · timeout ${pct(worst.timeoutPct)}`} kind="bad" />
      </div>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>트레이드오프 — 반영률을 올리면 지연이 오른다</h2>
        <div className="hint">각 점이 하나의 정책이다. x = 최신 반영률(%), y = p95 지연(ms).</div>
        <LineChart series={d.tradeoff} xLabel="최신 반영률(%)" xMin={30} xMax={100} y0 width={760} height={250} />
        <ChartLegend series={d.tradeoff} />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>어디서 신선도가 소모되는가</h2>
        <div className="hint">정책과 무관하게, 병목은 consumer 구간이다.</div>
        <Bars
          items={d.breakdown.map((b, i) => ({
            label: b.label,
            value: b.value,
            display: ms(b.value),
            color: i === 1 ? 'var(--bad)' : 'var(--info)',
          }))}
        />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>정책별 결과</h2>
        <DataTable
          columns={[
            { key: 'label', label: '정책' },
            { key: 'freshnessPct', label: '최신 반영률', num: true, fmt: (v) => pct(v as number) },
            { key: 'p95Ms', label: 'p95(ms)', num: true },
            { key: 'timeoutPct', label: 'timeout', num: true, fmt: (v) => pct(v as number) },
          ]}
          rows={d.policies}
        />
      </section>
      <section className="panel">
        <h2>결론과 트레이드오프</h2>
        <ConclusionBlock c={d.conclusion} />
      </section>
      <Harness text={d._harness} />
    </>
  );
}

/* ---------------- E2. online/offline 일치율 ---------------- */

export function ConsistencyScreen({ d }: { d: ConsistencyFixture }) {
  const miss = d.replay.contexts - d.replay.matched;
  return (
    <>
      <div className="grid g4" style={{ marginBottom: 16 }}>
        <Kpi label="일치율" value={pct(d.replay.matchPct)} desc={`컨텍스트 ${n0(d.replay.contexts)}건 replay`} />
        <Kpi label="불일치" value={`${n0(miss)}건`} desc={`원인 ${d.causes.length}종으로 분해`} />
        <Kpi label="최대 원인" value={d.causes[0].label.split(' ')[0]} desc={`${pct(d.causes[0].sharePct)} — consumer 지연`} />
        <Kpi label="측정 방법" value="동일 로그" desc="offline 재계산 대조" kind="warn" />
      </div>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>불일치 원인 분포</h2>
        <div className="hint">총계만 보면 “6% 불일치”로 끝난다. 원인을 나눠야 고칠 곳이 보인다.</div>
        <Bars items={d.causes.map((c) => ({ label: c.label, value: c.value, display: `${n0(c.value)}건 · ${pct(c.sharePct)}`, color: c.color }))} />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>대표 사례</h2>
        <DataTable
          columns={[
            { key: 'context', label: '컨텍스트' },
            { key: 'offline', label: 'offline', num: true },
            { key: 'online', label: 'online', num: true },
            { key: 'cause', label: '원인' },
          ]}
          rows={d.examples}
        />
      </section>
      <section className="panel">
        <h2>결론과 트레이드오프</h2>
        <ConclusionBlock c={d.conclusion} badgeKind="info" badgeLabel="경계" />
      </section>
      <Harness text={d._harness} />
    </>
  );
}

/* ---------------- E3. 과부하 degradation ---------------- */

export function OverloadScreen({ d }: { d: OverloadFixture }) {
  const at = (rps: number, policy: string) => d.rows.find((r) => r.rps === rps && r.policy === policy)!;
  const ub = at(900, 'unbounded');
  const ad = at(900, 'admission');
  return (
    <>
      <div className="grid g4" style={{ marginBottom: 16 }}>
        <Kpi label="SLO" value={`p95 ${d.slo.p95Ms}ms`} desc="이 기준으로 판정" />
        <Kpi label="unbounded @900rps" value={ms(ub.p95Ms)} desc={`timeout ${pct(ub.timeoutPct)}`} kind="bad" />
        <Kpi label="admission @900rps" value={ms(ad.p95Ms)} desc={`SLO 안 · coverage ${pct(ad.coveragePct, 0)}`} kind="good" />
        <Kpi label="포기한 개인화" value={pct(100 - ad.coveragePct)} desc="SLO를 지키는 대가" kind="warn" />
      </div>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>유입을 올리면 — p95</h2>
        <div className="hint">SLO p95 {d.slo.p95Ms}ms 기준선을 눈으로 넘는 지점을 보라.</div>
        <LineChart series={d.series} xLabel="유입(rps)" xTicks={[100, 600, 900]} y0 width={760} height={260} />
        <ChartLegend series={d.series} />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>정책별 결과</h2>
        <div className="hint">coverage = 모델을 실제로 쓴 요청 비율. 나머지는 폴백으로 응답한다.</div>
        <DataTable
          columns={[
            { key: 'rps', label: '유입(rps)', num: true },
            { key: 'policy', label: '정책' },
            { key: 'p95Ms', label: 'p95(ms)', num: true },
            { key: 'p99Ms', label: 'p99(ms)', num: true },
            { key: 'timeoutPct', label: 'timeout', num: true, fmt: (v) => pct(v as number) },
            { key: 'fallbackPct', label: '폴백', num: true, fmt: (v) => pct(v as number) },
            { key: 'coveragePct', label: 'coverage', num: true, fmt: (v) => pct(v as number) },
          ]}
          rows={d.rows}
        />
      </section>
      <section className="panel">
        <h2>결론과 트레이드오프</h2>
        <ConclusionBlock c={d.conclusion} />
      </section>
      <Harness text={d._harness} />
    </>
  );
}

/* ---------------- E4. 제약과 재검증 비용 ---------------- */

export function ConstraintsScreen({ d }: { d: ConstraintsFixture }) {
  const m = d.modes;
  return (
    <>
      <div className="grid g4" style={{ marginBottom: 16 }}>
        <Kpi label={`${m[0].mode} 위반율`} value={pct(m[0].violationPct)} desc="추가 지연 0ms" kind="bad" />
        <Kpi label={m[2].mode} value={pct(m[2].violationPct)} desc={`+${m[2].addedMs}ms · stale ${m[2].staleWindowMs}ms`} kind="warn" />
        <Kpi label={m[3].mode} value={pct(m[3].violationPct)} desc={`+${m[3].addedMs}ms`} kind="good" />
        <Kpi label="위반 1건 줄이는 값" value={`+${m[3].addedMs - m[0].addedMs}ms`} desc="요청마다 지불" />
      </div>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>위반율을 낮추는 값</h2>
        <div className="hint">x = 추가 지연(ms), y = 제약 위반율(%). 왼쪽 아래가 공짜, 오른쪽 아래가 안전.</div>
        <LineChart series={d.tradeoff} xLabel="추가 지연(ms)" y0 width={760} height={240} />
        <ChartLegend series={d.tradeoff} />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>검증 시점별 결과</h2>
        <div className="hint">stale 창 = 검증 시점과 응답 시점의 간격. 그 사이 사실이 바뀌면 통과한 제약이 무효가 된다.</div>
        <DataTable
          columns={[
            { key: 'mode', label: '검증 시점' },
            { key: 'addedMs', label: '추가 지연(ms)', num: true },
            { key: 'violationPct', label: '위반율', num: true, fmt: (v) => pct(v as number) },
            { key: 'staleWindowMs', label: 'stale 창(ms)', num: true },
            { key: 'factsChanged', label: '창 안에서 바뀐 사실', num: true },
          ]}
          rows={d.modes}
        />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>경계 — 추천은 어디까지, 구매는 어디부터</h2>
        <p style={{ margin: '0 0 10px' }}>{d.boundary.statement}</p>
        <Badge kind="info">권고 — {d.boundary.recommendation}</Badge>
      </section>
      <section className="panel">
        <h2>결론</h2>
        <ConclusionBlock c={d.conclusion} badgeKind="info" badgeLabel="경계" />
      </section>
      <Harness text={d._harness} />
    </>
  );
}

/* ---------------- E5. 생성 범위와 계산 예산 ---------------- */

export function BudgetScreen({ d }: { d: BudgetFixture }) {
  const rank = d.modes[0];
  const full = d.modes[d.modes.length - 1];
  const mid = d.modes[1];
  return (
    <>
      <div className="grid g4" style={{ marginBottom: 16 }}>
        <Kpi label="latency 예산" value={ms(d.budgetMs)} desc="이 안에 다 넣어야 한다" />
        <Kpi label={`${full.mode} e2e`} value={ms(full.e2eMs)} desc={`예산의 ${pct((full.e2eMs / d.budgetMs) * 100, 0)} 소진`} kind="bad" />
        <Kpi label="throughput" value={`${n0(full.throughputRps)}rps`} desc={`${rank.mode} ${n0(rank.throughputRps)}rps 대비`} kind="bad" />
        <Kpi label={`${mid.mode} e2e`} value={ms(mid.e2eMs)} desc={`예산의 ${pct((mid.e2eMs / d.budgetMs) * 100, 0)}`} kind="warn" />
      </div>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>모델이 e2e에서 차지하는 몫</h2>
        <div className="hint">같은 하드웨어로 처리할 수 있는 요청 수(throughput)가 함께 떨어진다.</div>
        <Bars items={d.share} />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>구간별 분해</h2>
        <div className="hint">context·constraint·직렬화는 거의 고정이다. 늘어나는 건 inference뿐이다.</div>
        <DataTable
          columns={[
            { key: 'mode', label: '모드' },
            { key: 'contextMs', label: 'context', num: true },
            { key: 'rankMs', label: 'ranking', num: true },
            { key: 'arMs', label: 'AR', num: true },
            { key: 'constraintMs', label: 'constraint', num: true },
            { key: 'postMs', label: '직렬화', num: true },
            { key: 'e2eMs', label: 'e2e(ms)', num: true },
            { key: 'throughputRps', label: 'rps', num: true },
          ]}
          rows={d.modes}
        />
      </section>
      <section className="panel">
        <h2>결론과 트레이드오프</h2>
        <ConclusionBlock c={d.conclusion} badgeKind="info" badgeLabel="경계" />
      </section>
      <Harness text={d._harness} />
    </>
  );
}

/* ---------------- E7. 요청 1건 추적 ---------------- */

export function TraceScreen({ d }: { d: TraceFixture }) {
  const inference = d.spans.find((s) => s.name.toLowerCase().includes('inference'));
  return (
    <>
      <div className="grid g4" style={{ marginBottom: 16 }}>
        <Kpi label="요청" value={d.requestId} desc={`${d.userId} · ${d.at.slice(11, 19)}`} />
        <Kpi label="총 시간" value={ms(d.totalMs)} desc={inference ? `inference가 ${pct((inference.ms / d.totalMs) * 100, 0)}` : ''} />
        <Kpi label="stale" value={ms(d.stale.lastEventAgeMs)} desc={d.stale.reason} kind="warn" />
        <Kpi label="출력" value={`${n0(d.output.items)}개`} desc={`제약으로 ${d.output.droppedByConstraint}개 제외`} />
      </div>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>타임라인</h2>
        <div className="hint">막대 위치 = 시작 시각, 폭 = 소요 시간. 전체 {ms(d.totalMs)}.</div>
        <Timeline spans={d.spans} total={d.totalMs} />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>stale — 무엇이 얼마나 낡았나</h2>
        <DataTable
          columns={[
            { key: 'k', label: '항목' },
            { key: 'v', label: '값' },
          ]}
          rows={[
            { k: '마지막 이벤트 나이', v: ms(d.stale.lastEventAgeMs) },
            { k: '원인', v: d.stale.reason },
            { k: '영향 피처', v: d.stale.affectedFeature },
            { k: 'offline 값', v: n0(d.stale.offlineValue) },
            { k: 'online 값', v: n0(d.stale.onlineValue) },
          ]}
        />
      </section>
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>제약 검증</h2>
        <div className="hint">검증을 통과했더라도 그 시점 이후 사실이 바뀌면 응답은 거짓이 된다.</div>
        <DataTable
          columns={[
            { key: 'itemId', label: '상품' },
            { key: 'check', label: '검사' },
            { key: 'state', label: '결과' },
            { key: 'checkedAt', label: '검증 시점' },
          ]}
          rows={d.constraints}
        />
      </section>
      <section className="panel">
        <h2>입력 스냅샷 (재현용)</h2>
        <div className="hint">이 응답을 다시 만들어보려면 무엇을 넣었는지가 남아 있어야 한다.</div>
        <DataTable
          columns={[
            { key: 'k', label: '키' },
            { key: 'v', label: '값' },
          ]}
          rows={[
            { k: 'lastViewedItem', v: d.inputSnapshot.lastViewedItem },
            { k: 'recentCategories', v: d.inputSnapshot.recentCategories.join(', ') },
            { k: 'clickCount1h', v: n0(d.inputSnapshot.clickCount1h) },
            { k: 'contextVersion', v: d.inputSnapshot.contextVersion },
          ]}
        />
      </section>
    </>
  );
}

/* ---------------- 레지스트리 ---------------- */

export const EXPERIMENT_SCREENS: Record<string, { title: string; question: string; render: (d: never) => React.ReactNode }> = {
  cache: {
    title: '캐시 값 크기와 압축 임계값',
    question: '캐시는 DB 조회를 없애도 네트워크 비용은 없애지 않는다. 값이 커지면 GET이 tail에서 튄다. 그럼 언제부터 압축이 이득인가.',
    render: (d) => <CacheScreen d={d as CacheFixture} />,
  },
  freshness: {
    title: '신선도 vs 지연',
    question: '클릭 이벤트는 Kafka에 들어갔지만 consumer가 못 따라가면 모델은 최신 행동을 모른 채 생성한다. 최신성을 사려면 얼마를 내야 하는가.',
    render: (d) => <FreshnessScreen d={d as FreshnessFixture} />,
  },
  consistency: {
    title: 'online/offline 컨텍스트 일치율',
    question: '같은 이벤트 로그로 만든 학습 피처와 서빙 피처가 다르면 모델이 같아도 입력이 다르다. 어디서 얼마나 갈라지는가.',
    render: (d) => <ConsistencyScreen d={d as ConsistencyFixture} />,
  },
  overload: {
    title: '과부하와 degradation',
    question: '모델 서버가 느려지면 queue가 길어지고 살아 있어도 API는 timeout 난다. SLO를 지키려면 모델 사용을 얼마나 포기해야 하는가.',
    render: (d) => <OverloadScreen d={d as OverloadFixture} />,
  },
  constraints: {
    title: '제약과 재검증 비용',
    question: '생성 중 재고가 0이 되면 제약을 통과한 출력도 응답 시점엔 거짓일 수 있다. 언제 다시 확인하고 얼마를 내는가.',
    render: (d) => <ConstraintsScreen d={d as ConstraintsFixture} />,
  },
  budget: {
    title: '생성 범위와 계산 예산',
    question: '홈 전체를 autoregressive로 생성하면 inference가 길어진다. 범위를 늘린 대가가 전체 예산에서 얼마인가.',
    render: (d) => <BudgetScreen d={d as BudgetFixture} />,
  },
  trace: {
    title: '요청 1건 추적',
    question: '평균은 어디가 느린지 알려주지 않는다. 요청 하나를 골라 어느 구간에서 얼마를 썼고 무엇이 stale이었는지 본다.',
    render: (d) => <TraceScreen d={d as TraceFixture} />,
  },
};
