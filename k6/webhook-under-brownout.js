import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * #230 — PG 가 느려질 때 웹훅 응답이 10초 규약을 지키는가.
 *
 * 토스는 10초 안에 2xx 를 못 받으면 재전송한다. 컨트롤러는 PG 를 안 부르지만(저장하고 바로 200),
 * <b>결제 요청과 톰캣 워커를 공유한다.</b> PG 가 느려 워커가 묶이면 웹훅도 같은 큐에 선다.
 *
 * 그래서 결제 부하와 웹훅을 <b>같이</b> 밀어 넣는다. 웹훅만 재면 이 문제가 안 보인다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PAY_RATE = Number(__ENV.PAY_RATE || 50);
const HOOK_RATE = Number(__ENV.HOOK_RATE || 10);
const DURATION = __ENV.DURATION || '45s';
const TOSS_SLA_MS = 10000;   // 토스 규약

const hookMs = new Trend('webhook_ms', true);
const hookOverSla = new Rate('webhook_over_10s');
const hookOk = new Counter('webhook_2xx');
const hookBad = new Counter('webhook_non2xx');

export const options = {
  scenarios: {
    // 결제 부하 — 워커를 묶는 쪽이다.
    pay: {
      executor: 'constant-arrival-rate',
      rate: PAY_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 200, maxVUs: 400,
      exec: 'payOnce',
    },
    // 웹훅 — 재려는 쪽이다. PG 를 안 부르므로 구조상 빨라야 한다.
    hook: {
      executor: 'constant-arrival-rate',
      rate: HOOK_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 40, maxVUs: 120,
      exec: 'webhookOnce',
    },
  },
  thresholds: {
    'http_req_duration{name:webhook}': ['p(95)<60000'],
    'http_req_duration{name:confirm}': ['p(95)<60000'],
  },
};

function jsonHeaders(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h['Authorization'] = `Bearer ${token}`;
  return h;
}

export function setup() {
  const login = http.post(`${BASE}/api/v1/auth/login`,
    JSON.stringify({ username: '1', password: 'user-local-only' }),
    { headers: jsonHeaders(null), tags: { name: 'login' } });
  check(login, { 'login 200': (r) => r.status === 200 });
  return { token: login.json('token') };
}

export function payOnce(data) {
  // 주문만 만들면 PG 를 안 부른다 — 승인까지 가야 워커가 PG 응답에 묶인다.
  // 첫 회차에서 이걸 빠뜨려 PG 지연 5초인데 결제 p95 가 13ms 로 나왔다(측정이 아무것도 안 됐다).
  const auth = `Bearer ${data.token}`;
  const orderRes = http.post(`${BASE}/api/v1/orders`,
    JSON.stringify({ items: [{ productId: 1, quantity: 1 }] }),
    { headers: jsonHeaders(data.token), tags: { name: 'order' }, timeout: '60s' });
  if (orderRes.status !== 201) return;
  const order = orderRes.json();

  http.post(`${BASE}/api/v1/payments/confirm`,
    JSON.stringify({ paymentKey: `wh-${uuidv4()}`, orderNo: order.orderNo, amount: order.totalAmount }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4(), Authorization: auth },
      tags: { name: 'confirm' }, timeout: '60s',
    });
}

export function webhookOnce() {
  // 매번 다른 paymentKey 로 보낸다. 같은 값이면 멱등 경로만 타서 저장 비용이 안 걸린다.
  const body = JSON.stringify({
    eventType: 'PAYMENT_STATUS_CHANGED',
    data: { paymentKey: `k6-${uuidv4()}`, status: 'DONE' },
  });
  const res = http.post(`${BASE}/api/v1/webhooks/toss`, body,
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'webhook' }, timeout: '60s' });
  hookMs.add(res.timings.duration);
  hookOverSla.add(res.timings.duration > TOSS_SLA_MS);
  if (res.status >= 200 && res.status < 300) hookOk.add(1); else hookBad.add(1);
}
