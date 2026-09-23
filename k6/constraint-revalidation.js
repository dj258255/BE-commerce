// E4 — 제약을 언제 다시 확인하는가. 추천을 흘리면서 **생성 중에 사실을 바꾼다**.
//
// 왜 두 시나리오인가: 이 실험의 전제는 "확인과 응답 사이에 사실이 바뀐다"이다. 그 변화를
// 요청 안에서 만들면(모델 스텁이 직접 소진) 정책이 개입할 여지가 사라진다 — **다른 사용자가
// 동시에 사는 상황**을 재현해야 한다. 그래서 `flip` 시나리오가 추천 부하와 **동시에** 돌며
// 재고를 바꾼다. 그래야 어떤 요청에게는 그 변화가 생성 도중에 일어난다.
//
// E4 후속에서 바뀐 것 둘:
//  ① **크기를 고정한다.** 이전에는 consume·release 를 따로 돌려 같은 항목이 왕복했고, 그래서
//     품절 집합 **크기가 통제되지 않았다**(NONE 의 위반율이 정책이 아니라 집합 크기를 따라감).
//     이제 `swap`(팔리는 것 하나 소진 + 품절된 것 하나 입고)만 써서 크기를 K 로 고정하고,
//     응답의 unavailableCount 가 K 로 유지되는지 **지표로 확인**한다(제어가 안 되면 런이 무효다).
//  ② **원천이 실제 재고다.** 확인이 DB 왕복을 하므로 확인 비용이 처음으로 보인다 — `checkMs`.
//
// 지표는 응답이 스스로 보고한 값을 쓴다:
//   violations          응답에 나간 팔 수 없는 상품 수 (서버가 **바깥에서 다시 대조**한 값)
//   servingMs           정책 경로 시간 — 계기(audit)를 뺀 값. 모델 지연(50ms)이 대부분이다
//   checkMs             **확인에 쓴 시간** — E4 후속의 비용 축(0회 / 1회 / 1회 / 2회)
//   snapshotAgeMs       확인에 쓴 사실이 응답 시점에 얼마나 낡았는가 = E4 의 "stale 창"
//   changesInWindow     그 창 안에서 사실이 몇 번 바뀌었는가 — 위반율과 함께 읽어야 한다
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 30);
const FLIP_RATE = Number(__ENV.FLIP_RATE || 8);
const FLIP_VUS = Number(__ENV.FLIP_VUS || 3);
const VUS = Number(__ENV.VUS || 20);
const DURATION = __ENV.DURATION || '60s';
// 서버의 app.recommendation.result-size 와 같아야 한다. 다르면 "다 채웠는가" 판정이 틀어진다.
const RESULT_SIZE = Number(__ENV.RESULT_SIZE || 12);
const WARMUP_MS = Number(__ENV.WARMUP_MS || 15000);
const SOLD_OUT_TARGET = Number(__ENV.SOLD_OUT_TARGET || 6);

const violation = new Rate('constraint_violation');         // 위반이 1건이라도 나갔는가
const violationsPer = new Trend('constraint_violations', true); // 응답당 위반 건수
const servingMs = new Trend('recommend_serving_ms', true);   // 정책 경로(계기 제외)
const checkMs = new Trend('recommend_check_ms', true);       // 확인에 쓴 시간(비용 축)
const auditedMs = new Trend('recommend_audit_ms', true);     // 계기 자체의 비용
const filteredTrend = new Trend('constraint_filtered', true); // 확인이 뺀 개수
const windowChanges = new Trend('constraint_window_changes', true);
const snapshotAge = new Trend('constraint_snapshot_age_ms', true);
const coverage = new Rate('recommend_coverage');
const flips = new Counter('constraint_flips');
const soldOut = new Trend('constraint_sold_out', true);      // 응답 시점의 품절 집합 크기
const control = new Rate('constraint_control_ok');           // 그 크기가 K 로 유지됐는가
// E4-b 의 핵심 지표. 사후 필터는 걸러낸 만큼 목록이 짧아지고, 생성 중 차단은 다음 후보로 메운다.
// 위반율만 보면 둘이 같아 보이는데(둘 다 0%), 사용자가 보는 화면은 다르다.
const itemCount = new Trend('recommend_item_count', true);
const fullList = new Rate('recommend_full_list');            // 요청한 개수를 그대로 채웠는가

export const options = {
  setupTimeout: '240s',
  scenarios: {
    recommend: {
      executor: 'constant-arrival-rate', exec: 'recommend',
      rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: VUS, maxVUs: VUS, gracefulStop: '5s',
    },
    flip: {
      executor: 'constant-arrival-rate', exec: 'flip',
      rate: FLIP_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: FLIP_VUS, maxVUs: FLIP_VUS,
      startTime: '2s',   // 추천이 먼저 흐르기 시작한 뒤에 바꾼다
    },
  },
  thresholds: { 'http_req_failed{expected_response:false}': ['rate<0.05'] },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

function jsonHeaders(token) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

export function setup() {
  const email = `k6-e4-${Date.now()}@load.test`;
  const password = 'k6-load-only-1234';
  const signup = http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'signup' } });
  check(signup, { 'signup 201': (r) => r.status === 201 });
  sleep(1);
  const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  check(login, { 'login 200': (r) => r.status === 200 });

  // 확인 대상 풀을 서버에서 받아 온다 — 하네스가 풀을 따로 알면 풀이 갈라질 수 있다.
  const pool = http.get(`${BASE}/api/v1/experiments/constraint/pool`,
    { headers: jsonHeaders(login.json('token')), tags: { name: 'pool' } });

  // 품절 집합 크기를 K 로 세운다 — 이후 변화는 swap 이라 크기가 유지된다.
  // (드라이버도 같은 값을 쓴다: SOLD_OUT_TARGET. 어긋나면 제어 확인이 거짓이 된다.)
  const prime = http.post(`${BASE}/api/v1/experiments/constraint/prime?count=${SOLD_OUT_TARGET}`, null,
    { headers: jsonHeaders(login.json('token')), tags: { name: 'prime' } });
  check(prime, { 'prime 200': (r) => r.status === 200 });
  check(prime, { 'prime 세팅': (r) => r.status === 200 && r.json('unavailableCount') === SOLD_OUT_TARGET });

  return { token: login.json('token'), pool: pool.json(), startedAt: Date.now() };
}

export function recommend(data) {
  const warming = Date.now() - data.startedAt < WARMUP_MS;
  const res = http.get(`${BASE}/api/v1/recommendations`,
    { headers: jsonHeaders(data.token), tags: { name: 'recommend' } });

  if (res.status === 200) {
    const body = res.json();
    if (!warming) {
      coverage.add(body.source === 'MODEL');
      violation.add(body.violations > 0);
      violationsPer.add(body.violations || 0);
      servingMs.add(body.servingMs);
      checkMs.add(body.checkMs === null ? 0 : body.checkMs);
      auditedMs.add(body.auditMs);
      filteredTrend.add(body.filteredByConstraint || 0);
      const n = (body.items || []).length;
      itemCount.add(n);
      fullList.add(n >= RESULT_SIZE);
      windowChanges.add(body.changesInWindow === null ? 0 : body.changesInWindow);
      snapshotAge.add(body.snapshotAgeMs === null ? -1 : body.snapshotAgeMs);
    }
  }
  check(res, { 'recommend 200': (r) => r.status === 200 });
}

/**
 * 다른 사용자가 사고, 다시 입고된다 — **크기를 보존한 채** 어떤 것이 품절인지만 바꾼다.
 * 응답의 unavailableCount 로 그 크기가 K 로 유지되는지 확인한다(제어 확인).
 */
export function flip(data) {
  const res = http.post(`${BASE}/api/v1/experiments/constraint/swap?count=${SOLD_OUT_TARGET}`, null,
    { headers: jsonHeaders(data.token), tags: { name: 'swap' } });
  if (res.status === 200) {
    const body = res.json();
    flips.add(body.changed || 0);
    const count = body.unavailableCount;
    soldOut.add(count);
    control.add(count === SOLD_OUT_TARGET);
  }
}

export function handleSummary(data) {
  const m = (name) => (data.metrics[name] || { values: {} }).values;
  const pct = (v) => (v === undefined || v === null ? 'n/a' : (v * 100).toFixed(2) + '%');
  const ms = (v) => (v === undefined || v === null ? 'n/a' : v.toFixed(2));
  const line = [
    `policy=${__ENV.CONSTRAINT_POLICY || '?'}`,
    `flipRate=${FLIP_RATE}/s`,
    `soldOutTarget=${SOLD_OUT_TARGET}`,
    `violationRate=${pct(m('constraint_violation').rate)}`,
    `violationsPerResp=${ms(m('constraint_violations').avg)}`,
    `coverage=${pct(m('recommend_coverage').rate)}`,
    `serving_med=${ms(m('recommend_serving_ms').med)}ms`,
    `serving_p95=${ms(m('recommend_serving_ms')['p(95)'])}ms`,
    `check_med=${ms(m('recommend_check_ms').med)}ms`,
    `check_p95=${ms(m('recommend_check_ms')['p(95)'])}ms`,
    `audit_med=${ms(m('recommend_audit_ms').med)}ms`,
    `filtered_avg=${ms(m('constraint_filtered').avg)}`,
    `windowChanges_avg=${ms(m('constraint_window_changes').avg)}`,
    `snapshotAge_med=${ms(m('constraint_snapshot_age_ms').med)}ms`,
    `soldOut_med=${ms(m('constraint_sold_out').med)}`,
    `controlOk=${pct(m('constraint_control_ok').rate)}`,
    `flips=${m('constraint_flips').count || 0}`,
    `iters=${m('iterations').count || 0}`,
    `dropped=${m('dropped_iterations').count || 0}`,
  ].join('  ');
  return { stdout: '\n[E4] ' + line + '\n' };
}
