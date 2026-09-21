// #172 — 검색 베이스라인의 **현재 경로**(MySQL `WHERE` + `INDEX`) 지연을 잰다.
//
// 왜 이걸 먼저 재나: 이슈 #172 가 판정 기준을 **측정 전에** 정해 뒀다 —
//   · 목록 p95 가 **300ms** 를 넘고 그 원인이 집계라면 → 검색 엔진 검토
//   · 패싯 집계가 **전체 지연의 50% 이상**이면 → 사전 집계 또는 엔진 검토
//   · 둘 다 아니면 → **도입하지 않는다**(운영 대상 추가는 그 자체로 비용)
// 그 기준에 숫자를 넣으려면 현재 경로를 먼저 재야 한다.
//
// 왜 **닫힌 루프(constant-vus)인가**: 이슈가 "동시성 1·10·50"을 고정했다. 동시성별 지연을 보는
// 것이 목적이므로 VU 수를 고정한다. (E3 처럼 **용량**을 재는 실험이었다면 개방 루프가 맞다 —
// 닫힌 루프는 서버가 느려지면 부하도 같이 줄어든다. 여기서는 그 성질이 오히려 원하는 것이다:
// "동시성 N 에서 지연이 얼마인가"를 묻는다.)
//
// 쿼리는 이슈가 고정한 축을 그대로 쓴다: 필터 6개 조합 · 정렬 4종 · 페이지 깊이 1/100/400 · 검색어 · 패싯 2축.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:18080';
const VUS = Number(__ENV.VUS || 1);
const DURATION = __ENV.DURATION || '30s';

const QUERIES = [
  { name: 'list_filter', path: '/api/v1/products?category=ladieswear&colour=black&productType=Dress&minPrice=10000&maxPrice=100000&sort=price_asc&page=0&size=24' },
  { name: 'list_broad', path: '/api/v1/products?page=0&size=24' },
  { name: 'list_deep', path: '/api/v1/products?page=400&size=24' },
  { name: 'list_sort_newest', path: '/api/v1/products?category=ladieswear&sort=newest&page=0&size=24' },
  { name: 'search', path: '/api/v1/products?q=shirt&page=0&size=24' },
  { name: 'facet', path: '/api/v1/products/facets?category=ladieswear&colour=black' },
];

const trends = {};
const counters = {};
for (const q of QUERIES) {
  trends[q.name] = new Trend(`q_${q.name}_ms`, true);
  // 표본 수를 **따로 센다** — trend 요약에 count 가 안 실려 오는 경우가 있어(0 으로 찍혔다)
  // 지표를 하나 더 둔다. 표본 없는 수치는 이 저장소의 규칙에 어긋난다.
  counters[q.name] = new Counter(`q_${q.name}_n`);
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    browse: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: { 'http_req_failed{expected_response:false}': ['rate<0.01'] },
  summaryTrendStats: ['med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  // VU·반복마다 다른 쿼리를 돌린다 — 한 쿼리만 재면 다른 축의 지연을 못 본다.
  const q = QUERIES[(__VU + __ITER) % QUERIES.length];
  const res = http.get(BASE + q.path, { tags: { name: q.name } });
  check(res, { '200': (r) => r.status === 200 });
  if (res.status === 200) {
    trends[q.name].add(res.timings.duration);
    counters[q.name].add(1);
  }
}

/** 표의 원천이 되는 한 줄들 — 쿼리 종류마다 p50/p95/p99. */
export function handleSummary(data) {
  const m = (name) => (data.metrics[name] || { values: {} }).values;
  const num = (v) => (v === undefined || v === null ? 'n/a' : v.toFixed(1));
  const lines = [];
  for (const q of QUERIES) {
    const t = m(`q_${q.name}_ms`);
    // **표본 수를 함께 남긴다** — 없으면 재현할 수 없고, 재현 못 하는 수치는 근거가 아니다.
    const count = m(`q_${q.name}_n`).count ?? 0;
    lines.push(`[172] vus=${VUS} query=${q.name} p50=${num(t.med)}ms p95=${num(t['p(95)'])}ms `
      + `p99=${num(t['p(99)'])}ms max=${num(t.max)}ms count=${count}`);
  }
  lines.push(`[172] vus=${VUS} achieved=${num(m('http_reqs').rate)}/s reqs=${m('http_reqs').count || 0} `
    + `failed=${num((m('http_req_failed').rate || 0) * 100)}%`);
  return { stdout: '\n' + lines.join('\n') + '\n' };
}
