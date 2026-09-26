import http from 'k6/http';
import { Trend, Rate, Counter } from 'k6/metrics';

/**
 * #350 — 주문 타임라인(어드민) 조립의 처리량 무릎. read-capacity.js 와 같은 판정(평탄 p95 의 3배, 실제 도착률 95%).
 *
 *   k6 run -e BASE_URL=... -e ORDERS=orders.json -e STEPS=50,100,200 -e STEP_SECONDS=60 k6/timeline-capacity.js
 *
 * ORDERS 는 주문 번호 배열 JSON 이다. 열린 루프(constant-arrival-rate)로 단계마다 따로 잰다.
 */
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ORDERS = JSON.parse(open(__ENV.ORDERS));
const STEPS = (__ENV.STEPS || '50,100,200,400,700,1000').split(',').map(Number);
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 60);
const GAP_SECONDS = 5;

const stepLatency = {}, stepOk = {}, stepReqs = {}, stepErr = {};
STEPS.forEach((rate) => {
  stepLatency[rate] = new Trend(`p95_at_${rate}rps`, true);
  stepOk[rate] = new Rate(`ok_at_${rate}rps`);
  stepReqs[rate] = new Counter(`reqs_at_${rate}rps`);
  stepErr[rate] = new Counter(`err_at_${rate}rps`);
});

export const options = {
  scenarios: Object.fromEntries(STEPS.map((rate, i) => [`step_${rate}`, {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${STEP_SECONDS}s`,
    startTime: `${i * (STEP_SECONDS + GAP_SECONDS)}s`,
    preAllocatedVUs: Math.min(rate, 400), maxVUs: 1500, env: { STEP_RATE: String(rate) }, exec: 'hit',
  }])),
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: 'admin', password: __ENV.ADMIN_PASSWORD || 'admin-local-only' }),
    { headers: { 'Content-Type': 'application/json' } });
  return { token: res.json('token') };
}

export function hit(data) {
  const rate = Number(__ENV.STEP_RATE);
  const orderNo = ORDERS[Math.floor(Math.random() * ORDERS.length)];
  const res = http.get(`${BASE}/api/v1/admin/orders/${orderNo}/timeline`, { headers: { Authorization: `Bearer ${data.token}` } });
  stepReqs[rate].add(1);
  const ok = res.status === 200;
  stepOk[rate].add(ok);
  if (ok) stepLatency[rate].add(res.timings.duration); else stepErr[rate].add(1);
}
