import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * ADR-022 실측 — 느린 PG 앞에서 자원이 어디서 먼저 마르는가.
 *
 * checkout-load.js 와 다른 점은 <b>도착률 고정</b>이다. VU 를 올리는 방식은 느린 응답이
 * 그대로 도착률을 떨어뜨려(닫힌 모델) 포화를 못 본다. 초당 N 건을 일정하게 밀어 넣어야
 * 서버가 받아내지 못하는 지점이 드러난다.
 *
 * 전제:
 *   1. docker compose up -d mysql redis
 *   2. java -jar commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar \
 *        --payment.fake-pg.approve-latency-ms=<지연> --app.ratelimit.enabled=false
 *   3. k6 run -e RATE=30 -e DURATION=60s k6/pg-brownout.js
 *
 * 실패를 임계로 걸지 않는다. 이 스크립트는 회귀 감시가 아니라 <b>어디서 무너지는지</b>를
 * 보는 것이라, 무너지는 것 자체가 결과다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '60s';

// 미확정(202)은 실패가 아니다. 타임아웃을 조였을 때 이 값이 얼마나 느는지가 ADR-022 선택지 C 의 비용이다.
const pending = new Counter('checkout_pending_202');
const ok = new Counter('checkout_ok_200');
const rejected = new Counter('checkout_rejected_4xx');
const failed = new Counter('checkout_failed_5xx');

export const options = {
  scenarios: {
    // 배경 읽기. 상한의 값은 결제를 빠르게 하는 것이 아니라 <b>나머지 요청을 지키는 것</b>이라,
    // 결제만 재면 상한이 순손실로만 보인다. 같은 앱의 가벼운 조회를 같이 밀어 넣어 그것을 본다.
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
      // 느린 PG 에서는 요청 하나가 오래 살아 있으므로 VU 를 넉넉히 준다.
      // 여기가 모자라면 k6 가 도착률을 못 지키고, 그건 서버가 아니라 부하기의 한계다.
      preAllocatedVUs: Number(__ENV.VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 400),
    },
  },
  // 임계로 실패시키려는 게 아니라, 태그별 p95 를 요약에 찍게 하려고 건다(느슨하게 둔다).
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

  const res = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `brownout-${uuidv4()}`,
    orderNo: order.orderNo,
    amount: order.totalAmount,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': uuidv4(),
      Authorization: auth,
    },
    tags: { name: 'confirm' },
  });

  if (res.status === 200) ok.add(1);
  else if (res.status === 202) pending.add(1);
  else if (res.status >= 500) failed.add(1);
  else if (res.status >= 400) rejected.add(1);
}
