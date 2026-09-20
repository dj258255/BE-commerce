// E1 — 신선도 vs 지연. 이벤트를 일정률로 흘리고, 대기 상한을 바꿔가며 반영률과 지연을 잰다.
//
// 이 파일명은 이미 personalization/web/fixtures/exp-freshness.json 의 _harness 에 적혀 있었다.
// 화면이 목으로 그려 둔 비교군(즉시 / 50 / 100 / 200 / 최신까지)과 지표를 그대로 실측으로 채운다.
//
// 흐름(VU당 한 반복):
//   ① POST /activity  seq = (VU별 단조 증가)   ← 이벤트를 낸다
//   ② GET  /context?expectSeq=<seq>&waitMs=<WAIT_MS>
//        → reflected(내 이벤트가 반영됐는가) · stalenessMs · waitedMs 를 받아 기록
//
// 왜 expectSeq 를 같이 보내나: "반영됐는가"를 클라이언트가 시각으로 추측하면 서버 간 시계 차이에
// 기대게 된다. 서버가 순번으로 판정하게 하는 편이 정확하고, 시각 비교 코드가 사라진다.
//
// 왜 constant-arrival-rate 인가: 정책이 느려지면 VU 방식은 요청률이 같이 떨어진다 — 그러면
// "정책의 대가"와 "부하가 줄어든 효과"가 섞인다. 도착률을 고정해야 정책만 변수로 남는다.
// (부하를 못 따라가면 k6 가 dropped_iterations 로 알려준다 — 그 값도 결과에 적는다.)
//
// 실행은 tools/run-freshness-vs-latency.sh 가 맡는다(전달 방식 × 대기 정책을 돌린다).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNTS = Number(__ENV.ACCOUNTS || 15);
const MAX_VUS = Number(__ENV.MAX_VUS || 15);
const RATE = Number(__ENV.RATE || 20);            // 초당 이벤트 수 — 모든 정책에서 같게 유지한다
const DURATION = __ENV.DURATION || '60s';
const WAIT_MS = Number(__ENV.WAIT_MS || 0);       // 대기 정책 — 이 실험의 독립변수
const TYPE = __ENV.ACTIVITY_TYPE || 'CLICK';
// 워밍업 구간은 지표에 넣지 않는다. JIT·커넥션 풀·첫 페이지 캐시가 p95 를 통째로 지배해서,
// 특히 짧은 런에서 정책 간 비교가 워밍업에 묻힌다(스모크 런에서 실제로 write_p95 가 269ms 로 나왔다).
const WARMUP_MS = Number(__ENV.WARMUP_MS || 15000);

// 결과 지표
const reflected = new Rate('freshness_reflected');      // 최신 반영률 ← E1의 주 지표
const lagMs = new Trend('context_lag_ms', true);        // 읽은 컨텍스트가 얼마나 낡았는가
const waitedMs = new Trend('context_wait_ms', true);    // 실제로 기다린 시간(상한을 다 썼는가)
const e2eMs = new Trend('freshness_e2e_ms', true);      // 이벤트를 낸 시점 → 반영 확인까지(벽시계)
const writeMs = new Trend('activity_write_ms', true);   // 쓰기 경로 비용 ← 전달 방식 비교의 핵심
const readMs = new Trend('context_read_ms', true);
const gaveUp = new Counter('context_wait_gave_up');     // 상한까지 기다렸는데 못 받은 횟수

export const options = {
  discardResponseBodies: false,
  // setup 은 계정을 30개 만든다 — 가입·로그인이 각각 Argon2id 해싱(19MiB)을 태우고,
  // 앱이 막 뜬 직후라 느리다. 기본 60초로는 첫 런이 통째로 날아간다(실제로 겪었다).
  setupTimeout: '240s',
  scenarios: {
    freshness: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: MAX_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    // 실패율은 느슨하게 — 이 실험이 재는 것은 성공/실패가 아니라 반영률과 지연이다.
    'http_req_failed{expected_response:false}': ['rate<0.05'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

function jsonHeaders(token) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

/**
 * 계정을 미리 만들어 토큰 풀을 짓는다. VU 하나가 계정 하나를 전담한다 —
 * 유니크 제약이 (userId, seq) 라서, 여러 VU 가 같은 계정을 쓰면 seq 가 부딪힌다.
 * 가입도 IP 기준 제한을 타므로 간격을 둔다(spike-multi-account.js 와 같은 이유).
 */
export function setup() {
  if (ACCOUNTS < MAX_VUS) {
    throw new Error(`계정(${ACCOUNTS})이 최대 VU(${MAX_VUS})보다 적다 — VU당 계정 하나가 필요하다`);
  }
  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `k6-fresh-${run}-${i}@load.test`;
    const password = 'k6-load-only-1234';
    const signup = http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'signup' } });
    check(signup, { 'signup 201': (r) => r.status === 201 });

    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
    check(login, { 'login 200': (r) => r.status === 200 });
    if (login.status === 200) {
      tokens.push(login.json('token'));
    }
    // 가입/로그인 자체도 IP 기준 제한(5/s)을 탄다 — 간격을 두지 않으면 setup 이
    // 자기가 만든 제한에 걸려 토큰을 못 받는다(spike-multi-account.js 가 실제로 겪은 함정).
    sleep(0.5);
  }
  if (tokens.length < MAX_VUS) {
    throw new Error(`토큰 ${tokens.length}개 — 최대 VU ${MAX_VUS}개를 채우지 못했다`);
  }
  return { tokens, startedAt: Date.now() };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const headers = jsonHeaders(token);
  // VU별 단조 증가 — 계정을 전담하므로 전역에서도 유일하다(유니크 제약이 (userId, seq)).
  const seq = __ITER + 1;
  const itemId = 1000 + (__VU * 100000) + seq;
  // 워밍업 중에도 요청은 계속 보낸다(컨슈머가 따라오는 상태를 만들어야 한다). 지표만 안 담는다.
  const warming = Date.now() - data.startedAt < WARMUP_MS;

  const startedAt = Date.now();

  const write = http.post(`${BASE}/api/v1/personalization/activity`,
    JSON.stringify({ itemId, type: TYPE, seq }), { headers, tags: { name: 'activity' } });
  const read = http.get(
    `${BASE}/api/v1/personalization/context?expectSeq=${seq}&waitMs=${WAIT_MS}`,
    { headers, tags: { name: 'context' } });

  if (!warming) {
    writeMs.add(write.timings.duration);
    readMs.add(read.timings.duration);
  }

  if (write.status === 201 && read.status === 200) {
    const body = read.json();
    const ok = body.reflected === true;
    if (!warming) {
      reflected.add(ok);
      lagMs.add(body.stalenessMs == null ? 0 : body.stalenessMs);
      waitedMs.add(body.waitedMs || 0);
      e2eMs.add(Date.now() - startedAt);
      if (!ok) gaveUp.add(1);
    }
  } else if (!warming) {
    reflected.add(false);
    gaveUp.add(1);
  }

  check(write, { 'activity 201': (r) => r.status === 201 });
  check(read, { 'context 200': (r) => r.status === 200 });
}

/** 정책 한 줄을 stdout 으로 뽑는다 — 리포트의 표가 이 줄들을 모은 것이다. */
export function handleSummary(data) {
  const m = (name) => (data.metrics[name] || { values: {} }).values;
  const pct = (v) => (v === undefined || v === null ? 'n/a' : (v * 100).toFixed(1) + '%');
  const ms = (v) => (v === undefined || v === null ? 'n/a' : v.toFixed(1));

  const line = [
    `waitMs=${WAIT_MS}`,
    `reflected=${pct(m('freshness_reflected').rate)}`,
    `e2e_p95=${ms(m('freshness_e2e_ms')['p(95)'])}ms`,
    `e2e_p99=${ms(m('freshness_e2e_ms')['p(99)'])}ms`,
    `write_p95=${ms(m('activity_write_ms')['p(95)'])}ms`,
    `read_p95=${ms(m('context_read_ms')['p(95)'])}ms`,
    `lag_p95=${ms(m('context_lag_ms')['p(95)'])}ms`,
    `wait_p95=${ms(m('context_wait_ms')['p(95)'])}ms`,
    `gaveup=${m('context_wait_gave_up').count || 0}`,
    `req_failed=${pct(m('http_req_failed').rate)}`,
    `dropped=${m('dropped_iterations').count || 0}`,
  ].join('  ');

  return { stdout: '\n[E1] ' + line + '\n' };
}
