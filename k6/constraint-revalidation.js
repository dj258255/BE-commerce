// E4 — 제약을 언제 다시 확인하는가. 추천을 흘리면서 **생성 중에 사실을 바꾼다**.
//
// 왜 두 시나리오인가: 이 실험의 전제는 "확인과 응답 사이에 사실이 바뀐다"이다. 그 변화를
// 요청 안에서 만들면(모델 스텁이 직접 소진) 정책이 개입할 여지가 사라진다 — **다른 사용자가
// 동시에 사는 상황**을 재현해야 한다. 그래서 `flip` 시나리오가 추천 부하와 **동시에** 돌며
// 가용성을 소진·해제한다. 그래야 어떤 요청에게는 그 변화가 생성 도중에 일어난다.
//
// 지표는 응답이 스스로 보고한 값을 쓴다:
//   violations          응답에 나간 팔 수 없는 상품 수 (서버가 **바깥에서 다시 대조**한 값)
//   servingMs           정책 경로 시간 — 계기(audit)를 뺀 값. 계기를 섞으면 정책 비용처럼 보인다
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
const WARMUP_MS = Number(__ENV.WARMUP_MS || 15000);

const violation = new Rate('constraint_violation');         // 위반이 1건이라도 나갔는가
const violationsPer = new Trend('constraint_violations', true); // 응답당 위반 건수
const servingMs = new Trend('recommend_serving_ms', true);   // 정책 경로(계기 제외)
const auditedMs = new Trend('recommend_audit_ms', true);     // 계기 자체의 비용
const filteredTrend = new Trend('constraint_filtered', true); // 확인이 뺀 개수
const windowChanges = new Trend('constraint_window_changes', true);
const snapshotAge = new Trend('constraint_snapshot_age_ms', true);
const coverage = new Rate('recommend_coverage');
const flips = new Counter('constraint_flips');

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
      auditedMs.add(body.auditMs);
      filteredTrend.add(body.filteredByConstraint || 0);
      windowChanges.add(body.changesInWindow === null ? 0 : body.changesInWindow);
      snapshotAge.add(body.snapshotAgeMs === null ? -1 : body.snapshotAgeMs);
    }
  }
  check(res, { 'recommend 200': (r) => r.status === 200 });
}

/** 다른 사용자가 사고, 다시 입고된다 — 어떤 것이 팔리는지가 계속 바뀐다. */
export function flip(data) {
  const headers = jsonHeaders(data.token);
  const consumed = http.post(`${BASE}/api/v1/experiments/constraint/consume`, null,
    { headers, tags: { name: 'consume' } });
  if (consumed.status === 200 && consumed.json('changed') > 0) {
    flips.add(1);
  }
  http.post(`${BASE}/api/v1/experiments/constraint/release`, null,
    { headers, tags: { name: 'release' } });
}

export function handleSummary(data) {
  const m = (name) => (data.metrics[name] || { values: {} }).values;
  const pct = (v) => (v === undefined || v === null ? 'n/a' : (v * 100).toFixed(2) + '%');
  const ms = (v) => (v === undefined || v === null ? 'n/a' : v.toFixed(2));
  const line = [
    `policy=${__ENV.CONSTRAINT_POLICY || '?'}`,
    `flipRate=${FLIP_RATE}/s`,
    `violationRate=${pct(m('constraint_violation').rate)}`,
    `violationsPerResp=${ms(m('constraint_violations').avg)}`,
    `coverage=${pct(m('recommend_coverage').rate)}`,
    `serving_med=${ms(m('recommend_serving_ms').med)}ms`,
    `serving_p95=${ms(m('recommend_serving_ms')['p(95)'])}ms`,
    `audit_med=${ms(m('recommend_audit_ms').med)}ms`,
    `filtered_avg=${ms(m('constraint_filtered').avg)}`,
    `windowChanges_avg=${ms(m('constraint_window_changes').avg)}`,
    `snapshotAge_med=${ms(m('constraint_snapshot_age_ms').med)}ms`,
    `flips=${m('constraint_flips').count || 0}`,
    `iters=${m('iterations').count || 0}`,
    `dropped=${m('dropped_iterations').count || 0}`,
  ].join('  ');
  return { stdout: '\n[E4] ' + line + '\n' };
}
