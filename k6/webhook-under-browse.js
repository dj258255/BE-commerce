import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * #250 — 워커가 PG 가 아닌 이유로 마를 때 웹훅이 10초 규약을 지키는가.
 *
 * #230(webhook-under-brownout.js)에 조회 부하를 더한다. PG 동시 호출 상한(40)은 PG 를 부르는 워커만 묶는다.
 * 조회가 몰려 DB 커넥션(20개)을 두고 줄을 서면 워커가 그 줄에서 묶이고, 웹훅도 같은 워커 큐에 선다.
 * 세 부하 모두 열린 루프다 — 서버가 느려져도 도착률이 줄지 않는다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:18080';
const PAY_RATE = Number(__ENV.PAY_RATE || 50);
const HOOK_RATE = Number(__ENV.HOOK_RATE || 10);
const BROWSE_RATE = Number(__ENV.BROWSE_RATE || 0);
const DURATION = __ENV.DURATION || '45s';
const TOSS_SLA_MS = 10000;

const hookMs = new Trend('webhook_ms', true);
const hookOverSla = new Rate('webhook_over_10s');
const hookBad = new Counter('webhook_non2xx');
const browseMs = new Trend('browse_ms', true);
const browseShed = new Counter('browse_503');
const browseErr = new Counter('browse_error');
const browseOk = new Counter('browse_2xx');

// 조회 섞기: 검색(엔진 + DB 채우기) · 필터 목록(DB) · 넓은 목록(DB) · 패싯(캐시)
const BROWSE = [
  ...Array(4).fill(null).map((_, i) => ['search', `/api/v1/products?q=${['dress', 'shirt', 'trousers', 'sweater'][i]}&size=24`]),
  ...Array(3).fill(null).map((_, i) => ['list_filter',
    `/api/v1/products?category=${['ladieswear', 'menswear', 'kids'][i]}&colour=black&maxPrice=50000&sort=price_asc&size=24`]),
  ['list_broad', '/api/v1/products?page=3&size=24'],
  ['list_broad', '/api/v1/products?page=10&size=24'],
  ['facet', '/api/v1/products/facets?q=dress&category=ladieswear'],
];

const scenarios = {
  pay: {
    executor: 'constant-arrival-rate', rate: PAY_RATE, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 200, maxVUs: 600, exec: 'payOnce',
  },
  hook: {
    executor: 'constant-arrival-rate', rate: HOOK_RATE, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 40, maxVUs: 300, exec: 'webhookOnce',
  },
};
if (BROWSE_RATE > 0) {
  scenarios.browse = {
    executor: 'constant-arrival-rate', rate: BROWSE_RATE, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 300, maxVUs: 3000, exec: 'browseOnce',
  };
}

export const options = {
  scenarios, discardResponseBodies: true,
  // 요약에 이름별 지연이 실리게 한다(임계값은 판정이 아니라 요약을 얻는 용도다)
  thresholds: { 'http_req_duration{name:confirm}': ['p(95)<600000'], 'http_req_duration{name:webhook}': ['p(95)<600000'] },
};

function jsonHeaders(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h['Authorization'] = `Bearer ${token}`;
  return h;
}

export function setup() {
  const login = http.post(`${BASE}/api/v1/auth/login`,
    JSON.stringify({ username: '1', password: 'user-local-only' }),
    { headers: jsonHeaders(null), tags: { name: 'login' }, responseType: 'text' });
  check(login, { 'login 200': (r) => r.status === 200 });
  return { token: login.json('token') };
}

export function payOnce(data) {
  const orderRes = http.post(`${BASE}/api/v1/orders`,
    JSON.stringify({ items: [{ productId: 1, quantity: 1 }] }),
    { headers: jsonHeaders(data.token), tags: { name: 'order' }, timeout: '60s', responseType: 'text' });
  if (orderRes.status !== 201) return;
  const order = orderRes.json();
  http.post(`${BASE}/api/v1/payments/confirm`,
    JSON.stringify({ paymentKey: `wb-${uuidv4()}`, orderNo: order.orderNo, amount: order.totalAmount }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4(), Authorization: `Bearer ${data.token}` },
      tags: { name: 'confirm' }, timeout: '60s',
    });
}

export function webhookOnce() {
  const body = JSON.stringify({
    eventType: 'PAYMENT_STATUS_CHANGED',
    data: { paymentKey: `k6b-${uuidv4()}`, status: 'DONE' },
  });
  const res = http.post(`${BASE}/api/v1/webhooks/toss`, body,
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'webhook' }, timeout: '60s' });
  hookMs.add(res.timings.duration);
  hookOverSla.add(res.timings.duration > TOSS_SLA_MS);
  if (res.status < 200 || res.status >= 300) hookBad.add(1);
}

export function browseOnce() {
  const [name, path] = BROWSE[Math.floor(Math.random() * BROWSE.length)];
  const res = http.get(`${BASE}${path}`, { tags: { name: `browse_${name}` }, timeout: '60s' });
  browseMs.add(res.timings.duration);
  if (res.status === 503) browseShed.add(1);
  else if (res.status >= 200 && res.status < 300) browseOk.add(1);
  else browseErr.add(1);
}
