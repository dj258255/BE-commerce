// E3 — 과부하 degradation. 유입을 단계적으로 올리며 **개인화 coverage와 지연의 교환비**를 잰다.
//
// 흐름(VU당 한 반복):
//   ① GET /api/v1/recommendations  → source(MODEL|FALLBACK) · fallbackReason · modelMs · servingMs
//
// 읽기 전에: `dropped` 가 0 이어야 이 런을 믿을 수 있다. 0 이 아니면 도착률을 못 채운 것이고,
// 그때의 coverage·지연은 **우리가 말한 부하에서 나온 값이 아니다**.
//
// 왜 source 를 세나: 이 실험이 재는 것은 "얼마나 빨랐는가"가 아니라 **"SLO 를 지키려고 개인화를
// 얼마나 포기했는가"** 다. 응답 시간만 보면 폴백으로 빠르게 답한 것과 모델로 답한 것이 구분되지 않는다.
//
// 왜 constant-arrival-rate 인가: 정책이 느려지면 VU 방식은 요청률이 같이 떨어진다 — 그러면 "정책의
// 대가"와 "부하가 줄어든 효과"가 섞인다. 도착률을 고정해야 정책만 변수로 남는다.
//
// 왜 워밍업을 빼나: JIT·커넥션 풀·첫 요청이 p95 를 통째로 지배한다. 정책 간 비교가 워밍업에 묻힌다.
//
// 실행은 tools/run-inference-overload.sh 가 맡는다(정책 × 부하 단계를 돌린다).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
// 계정은 **VU보다 적어도 된다** — 이 실험의 요청은 순수 GET 이라 사용자별 상태를 만들지 않는다.
// (신선도 실험은 (userId, seq) 유니크 제약 때문에 VU당 계정 하나가 필요했지만 여기서는 아니다.)
const ACCOUNTS = Number(__ENV.ACCOUNTS || 8);
// 사용자당 심는 활동 수. 컨텍스트가 있어야 "개인화된 응답"과 "모델이 답한 응답"이 구분된다.
const ACTIVITY_PER_USER = Number(__ENV.ACTIVITY_PER_USER || 10);
// VU 예산은 **최악의 지연**에서 정해야 한다. 부족하면 k6 가 도착률을 못 채우고(dropped_iterations)
// 그 열이 거짓말이 된다 — 실제로 처음에 8 VU 로 320 req/s 를 시도해 2440건을 버렸다.
// 최악 = busy-timeout 400ms → 320/s × 0.45s ≈ 144 VU. 여유를 둔다.
const MAX_VUS = Number(__ENV.MAX_VUS || 200);
const RATE = Number(__ENV.RATE || 80);          // 초당 요청 — 이 실험의 부하 축
const DURATION = __ENV.DURATION || '40s';
const WARMUP_MS = Number(__ENV.WARMUP_MS || 10000);

const coverage = new Rate('rec_coverage');            // 모델이 답한 비율
// **개인화된 비율** — 모델이 답했고 **재료도 있었다**. E3 의 "personalization coverage" 는 이쪽이다.
// 둘을 나누지 않으면 모델이 빈손으로 답해도 coverage 가 100% 로 보인다(첫 실행이 그랬다).
const personalized = new Rate('rec_personalized');
const servingMs = new Trend('rec_serving_ms', true);
const modelMs = new Trend('rec_model_ms', true);
const fallbackRejected = new Counter('rec_fallback_rejected');
const fallbackTimeout = new Counter('rec_fallback_timeout');
const fallbackFailed = new Counter('rec_fallback_failed');
const zeroContext = new Counter('rec_zero_context');  // 모델을 불렀지만 재료가 없었던 횟수

export const options = {
  discardResponseBodies: false,
  setupTimeout: '240s',
  scenarios: {
    overload: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: MAX_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '10s',
    },
  },
  thresholds: {
    // 이 실험이 재는 것은 성공/실패가 아니라 coverage 와 지연이다 — 실패율은 느슨하게 둔다.
    'http_req_failed{expected_response:false}': ['rate<0.05'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

/** 계정을 미리 만들어 토큰 풀을 짓는다 — 로그인은 Argon2id 라 느리고, IP 제한(5/s)도 있다. */
export function setup() {
  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `k6-rec-${run}-${i}@load.test`;
    const password = 'k6-load-only-1234';
    const signup = http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'signup' } });
    check(signup, { 'signup 201': (r) => r.status === 201 });
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
    if (login.status === 200) {
      const token = login.json('token');
      tokens.push(token);
      // **개인화 재료를 심는다.** 이게 없으면 모델은 답하지만 개인화할 것이 없다 —
      // 첫 실행에서 zero_ctx 가 전부였고, 그래서 그 실행을 버렸다.
      for (let seq = 1; seq <= ACTIVITY_PER_USER; seq++) {
        http.post(`${BASE}/api/v1/personalization/activity`,
          JSON.stringify({ itemId: 700000 + i * 100 + seq, type: seq % 2 === 0 ? 'VIEW' : 'CLICK', seq }),
          { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
            tags: { name: 'seed-activity' } });
      }
    }
    sleep(0.5);   // 가입·로그인도 IP 기준 제한을 탄다 — 간격을 두지 않으면 setup 이 스스로 걸린다
  }
  if (tokens.length === 0) {
    throw new Error('토큰을 하나도 못 받았다 — 가입/로그인이 실패했다');
  }
  return { tokens, startedAt: Date.now() };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const warming = Date.now() - data.startedAt < WARMUP_MS;

  const response = http.get(`${BASE}/api/v1/recommendations`,
    { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'recommend' } });

  if (warming || response.status !== 200) {
    if (!warming) {
      coverage.add(false);
      personalized.add(false);
    }
    check(response, { 'recommend 200': (r) => r.status === 200 });
    return;
  }

  const body = response.json();
  const byModel = body.source === 'MODEL';
  coverage.add(byModel);
  personalized.add(byModel && (body.contextItems || 0) > 0);
  servingMs.add(body.servingMs || response.timings.duration);
  modelMs.add(body.modelMs || 0);
  if (body.fallbackReason === 'REJECTED') fallbackRejected.add(1);
  if (body.fallbackReason === 'TIMEOUT') fallbackTimeout.add(1);
  if (body.fallbackReason === 'FAILED') fallbackFailed.add(1);
  if (byModel && body.contextItems === 0) zeroContext.add(1);
  check(response, { 'recommend 200': (r) => r.status === 200 });
}

/** 한 줄을 stdout 으로 뽑는다 — 리포트의 표가 이 줄들을 모은 것이다. */
export function handleSummary(data) {
  const m = (name) => (data.metrics[name] || { values: {} }).values;
  const pct = (v) => (v === undefined || v === null ? 'n/a' : (v * 100).toFixed(1) + '%');
  const num = (v, digits = 1) => (v === undefined || v === null ? 'n/a' : v.toFixed(digits));
  const count = (name) => m(name).count || 0;

  const line = [
    `policy=${__ENV.POLICY || 'unknown'}`,
    `rate=${RATE}`,
    // **실제로 달성한 부하.** 이 값이 rate 보다 낮으면(=`dropped` 가 0이 아니면) 그 런의 coverage·지연은
    // 우리가 말한 부하에서 나온 값이 아니다. 정책이 앱을 마비시키면 달성률 자체가 결과의 일부가 된다.
    `achieved=${num(m('http_reqs').rate, 0)}`,
    `coverage=${pct(m('rec_coverage').rate)}`,
    `personalized=${pct(m('rec_personalized').rate)}`,
    `serving_p95=${num(m('rec_serving_ms')['p(95)'])}ms`,
    `serving_p99=${num(m('rec_serving_ms')['p(99)'])}ms`,
    `model_p95=${num(m('rec_model_ms')['p(95)'])}ms`,
    `rejected=${count('rec_fallback_rejected')}`,
    `timeout=${count('rec_fallback_timeout')}`,
    `failed=${count('rec_fallback_failed')}`,
    `zero_ctx=${count('rec_zero_context')}`,
    `req_failed=${pct(m('http_req_failed').rate)}`,
    `dropped=${count('dropped_iterations')}`,
  ].join('  ');

  return { stdout: '\n[E3] ' + line + '\n' };
}
