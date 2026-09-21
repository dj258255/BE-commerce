// E5 — 생성 범위·계산 예산. **페이지를 어떻게 만드는가**가 같은 하드웨어에서 무엇을 내주는지 잰다.
//
// 흐름(VU당 한 반복):
//   ① GET /api/v1/recommendations  → source · contextMs · modelMs · checkMs · servingMs
//
// 왜 constant-arrival-rate 인가: 범위가 느려지면 VU 방식은 요청률이 같이 떨어진다 — 그러면
// "범위의 대가"와 "부하가 줄어든 효과"가 섞인다. 도착률을 고정해야 범위만 변수로 남는다.
//
// 왜 `dropped` 를 보나: 0 이 아니면 도착률을 못 채운 것이고, 그 런의 coverage·지연은 우리가 말한
// 부하에서 나온 값이 아니다.
//
// 왜 구간을 나누나: 이 실험이 내는 답은 "**전체 예산에서 모델이 차지하는 몫**"이다. 총 시간만 보면
// 범위를 넓힌 대가가 어디로 갔는지 알 수 없다. 잔차(구간 합을 뺀 나머지)도 함께 남긴다 —
// 계기에 잡히지 않은 몫을 0으로 두면 예산이 실제보다 깔끔해 보인다.
//
// 왜 워밍업을 빼나: JIT·커넥션 풀·첫 요청이 p95 를 통째로 지배한다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNTS = Number(__ENV.ACCOUNTS || 8);
const ACTIVITY_PER_USER = Number(__ENV.ACTIVITY_PER_USER || 10);
const MAX_VUS = Number(__ENV.MAX_VUS || 200);
const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '40s';
const WARMUP_MS = Number(__ENV.WARMUP_MS || 10000);

const coverage = new Rate('gen_coverage');            // 모델이 답한 비율
const personalized = new Rate('gen_personalized');    // 모델이 답했고 재료도 있었다
const e2e = new Trend('gen_e2e_ms', true);            // 서버가 보고한 servingMs (모든 응답 = SLO)
const modelE2e = new Trend('gen_model_e2e_ms', true); // 모델 경로만의 servingMs (예산 분해의 기준)
const contextMs = new Trend('gen_context_ms', true);  // 예산의 컨텍스트 몫
const modelMs = new Trend('gen_model_ms', true);      // 예산의 추론(생성) 몫
const checkMs = new Trend('gen_check_ms', true);      // 예산의 제약 확인 몫
// **Trend 여야 한다.** Rate 로 두면 add(0.9) 가 "truthy"로 세어져 항상 100% 가 된다 — 실제로 그렇게
// 나왔고, 그 값은 "모델이 응답의 90%를 차지한다"가 아니라 "0 이 아닌 값을 넣었다"는 뜻이었다.
const modelShare = new Trend('gen_model_share', true); // 추론 / 전체 (0~1)
const fallbackRejected = new Counter('gen_fallback_rejected');
const fallbackTimeout = new Counter('gen_fallback_timeout');
const zeroContext = new Counter('gen_zero_context');

export const options = {
  discardResponseBodies: false,
  setupTimeout: '240s',
  scenarios: {
    budget: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: MAX_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '10s',
    },
  },
  thresholds: { 'http_req_failed{expected_response:false}': ['rate<0.05'] },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

/** 계정과 **개인화 재료**를 심는다 — 재료가 없으면 모델이 빈손으로 답해 coverage 가 부풀려진다. */
export function setup() {
  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `k6-gen-${run}-${i}@load.test`;
    const password = 'k6-load-only-1234';
    const signup = http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'signup' } });
    check(signup, { 'signup 201': (r) => r.status === 201 });
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
    if (login.status === 200) {
      const token = login.json('token');
      tokens.push(token);
      for (let seq = 1; seq <= ACTIVITY_PER_USER; seq++) {
        http.post(`${BASE}/api/v1/personalization/activity`,
          JSON.stringify({ itemId: 800000 + i * 100 + seq, type: seq % 2 === 0 ? 'VIEW' : 'CLICK', seq }),
          { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
            tags: { name: 'seed-activity' } });
      }
    }
    sleep(0.5);   // 가입·로그인도 IP 제한을 탄다
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
  const serving = body.servingMs || response.timings.duration;
  coverage.add(byModel);
  personalized.add(byModel && (body.contextItems || 0) > 0);
  // e2e 는 **모든 응답**을 잰다 — SLO 는 폴백도 포함해 지켜야 하는 약속이다.
  e2e.add(serving);
  // 구간 분해는 **모델 경로만** 잰다. 폴백은 모델을 안 부르므로(modelMs=0) 섞으면 모델 몫이
  // coverage 처럼 희석된다 — 실제로 그렇게 나와서 고쳤다(80/s 에서 44.5% = coverage 와 같은 값).
  if (byModel) {
    modelE2e.add(serving);
    contextMs.add(body.contextMs || 0);
    modelMs.add(body.modelMs || 0);
    checkMs.add(body.checkMs || 0);
    if (serving > 0) modelShare.add((body.modelMs || 0) / serving);
  }
  if (body.fallbackReason === 'REJECTED') fallbackRejected.add(1);
  if (body.fallbackReason === 'TIMEOUT') fallbackTimeout.add(1);
  if (byModel && body.contextItems === 0) zeroContext.add(1);
  check(response, { 'recommend 200': (r) => r.status === 200 });
}

/** 리포트의 표가 모으는 한 줄. 구간 중앙값과 **모델 몫**을 함께 남긴다. */
export function handleSummary(data) {
  const m = (name) => (data.metrics[name] || { values: {} }).values;
  const pct = (v) => (v === undefined || v === null ? 'n/a' : (v * 100).toFixed(1) + '%');
  const num = (v, digits = 1) => (v === undefined || v === null ? 'n/a' : v.toFixed(digits));
  const count = (name) => m(name).count || 0;

  const line = [
    `scope=${__ENV.GENERATION_SCOPE || '?'}`,
    `rate=${RATE}`,
    `achieved=${num(m('http_reqs').rate, 0)}`,
    `coverage=${pct(m('gen_coverage').rate)}`,
    `personalized=${pct(m('gen_personalized').rate)}`,
    `e2e_med=${num(m('gen_e2e_ms').med)}ms`,
    `e2e_p95=${num(m('gen_e2e_ms')['p(95)'])}ms`,
    `model_e2e_med=${num(m('gen_model_e2e_ms').med)}ms`,
    `model_e2e_p95=${num(m('gen_model_e2e_ms')['p(95)'])}ms`,
    `context_med=${num(m('gen_context_ms').med)}ms`,
    `model_med=${num(m('gen_model_ms').med)}ms`,
    `check_med=${num(m('gen_check_ms').med)}ms`,
    `model_share=${pct(m('gen_model_share').avg)}`,
    `rejected=${count('gen_fallback_rejected')}`,
    `timeout=${count('gen_fallback_timeout')}`,
    `zero_ctx=${count('gen_zero_context')}`,
    `req_failed=${pct(m('http_req_failed').rate)}`,
    `dropped=${count('dropped_iterations')}`,
  ].join('  ');

  return { stdout: '\n[E5] ' + line + '\n' };
}
