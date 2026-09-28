import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 데드라인 전파(#409, 27절⑦) 실측 — 이미 떠난 고객의 결제가 PG 슬롯을 얼마나 차지하는가.
 *
 * tools/run-pg-brownout.sh 를 그대로 넓혀 쓴다(k6 스크립트만 이걸로 바꿔 부른다). 다른 점은
 * 요청의 일부(LATE_FRACTION)에 <b>남은 시간이 0인</b> {@code X-Request-Timeout-Ms} 헤더를 실어 보낸다는
 * 것이다 — 고객이 화면 앞을 이미 떠났거나(느린 PG에 지쳐 포기) 데드라인이 안 늘어난 재시도를
 * 흉내낸다. 나머지(정상 몫)는 넉넉한 남은 시간(30초)을 보낸다.
 *
 * paymentKey 접두어로 두 몫을 나눠 DB 에서 따로 집계할 수 있다:
 *   late-*   데드라인이 이미 지난 요청
 *   normal-* 데드라인이 안 지난(또는 없는) 요청
 *
 * 서버 쪽 켬/끔은 payment.deadline-check.enabled 로 한다(같은 k6 스크립트로 넣기 전/후를 잰다):
 *   전: --payment.deadline-check.enabled=false (헤더는 받지만 무시 — 옛 동작)
 *   후: --payment.deadline-check.enabled=true  (기본값)
 *
 * 사용: k6 run -e BASE_URL=... -e RATE=30 -e DURATION=60s \
 *         -e LATE_FRACTION=0.3 -e LATE_TIMEOUT_MS=0 -e NORMAL_TIMEOUT_MS=30000 \
 *         k6/pg-brownout-deadline.js
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '60s';
const LATE_FRACTION = Number(__ENV.LATE_FRACTION || 0.3);
// 남은 시간(밀리초). 0 이하면 서버가 이미 지난 것으로 본다. 시계 값을 보내지 않으므로 k6와 서버의 시계 차이와 무관하다.
const LATE_TIMEOUT_MS = Number(__ENV.LATE_TIMEOUT_MS || 0);
const NORMAL_TIMEOUT_MS = Number(__ENV.NORMAL_TIMEOUT_MS || 30000);

const pending = new Counter('checkout_pending_202');
const ok = new Counter('checkout_ok_200');
const rejected = new Counter('checkout_rejected_4xx');
const failed = new Counter('checkout_failed_5xx');
// late/normal 몫을 나눠 센다 — DB 집계와 서로 대조하는 데 쓴다.
const lateOk = new Counter('checkout_late_ok_200');
const lateRejected = new Counter('checkout_late_rejected_4xx');
const normalOk = new Counter('checkout_normal_ok_200');
const normalPending = new Counter('checkout_normal_pending_202');
const normalRejected = new Counter('checkout_normal_rejected_4xx');

export const options = {
  scenarios: {
    reads: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.READ_RATE || 20),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 60,
      exec: 'readOrders',
    },
    arrival: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 400),
    },
  },
  thresholds: {
    'http_req_duration{name:read}': ['p(95)<60000'],
    'http_req_duration{name:confirm}': ['p(95)<60000'],
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    username: '1',
    password: __ENV.USER_PASSWORD || 'user-local-only',
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  check(res, { 'login 200': (r) => r.status === 200 });
  return { token: res.json('token') };
}

export function readOrders(data) {
  http.get(`${BASE}/api/v1/orders`, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { name: 'read' },
  });
}

export default function (data) {
  const auth = `Bearer ${data.token}`;

  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: 1, quantity: 1 }],
  }), {
    headers: { 'Content-Type': 'application/json', Authorization: auth },
    tags: { name: 'order' },
  });
  if (orderRes.status !== 201) {
    return;
  }
  const order = orderRes.json();

  const isLate = Math.random() < LATE_FRACTION;
  const timeoutMs = isLate ? LATE_TIMEOUT_MS : NORMAL_TIMEOUT_MS;
  const prefix = isLate ? 'late' : 'normal';

  const res = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `${prefix}-${uuidv4()}`,
    orderNo: order.orderNo,
    amount: order.totalAmount,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': uuidv4(),
      Authorization: auth,
      'X-Request-Timeout-Ms': String(timeoutMs),
    },
    tags: { name: 'confirm' },
  });

  if (res.status === 200) { ok.add(1); isLate ? lateOk.add(1) : normalOk.add(1); }
  else if (res.status === 202) { pending.add(1); if (!isLate) normalPending.add(1); }
  else if (res.status >= 500) { failed.add(1); }
  else if (res.status >= 400) { rejected.add(1); isLate ? lateRejected.add(1) : normalRejected.add(1); }
}
