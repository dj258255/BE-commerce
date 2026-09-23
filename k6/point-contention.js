import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 포인트 적립 경합 실측 (docs/performance/point-accrual-contention.md)
 *
 * 같은 계정 하나로 30VU가 동시에 주문→승인을 반복한다 — 모든 승인이 같은
 * point_accounts 행에 +1%를 적립하며 경합하는 최악 조건. 운영에선 per-user
 * rate limit(5/s)이 이 동시성을 가리므로, 반드시 limiter를 끄고 돌린다:
 *   APP_RATELIMIT_ENABLED=false ./gradlew -p commerce bootRun
 *
 * 판독: confirm 2xx 체크 성공률. 낙관적 락(현행)에서는 버전 충돌로 대량
 * 실패하고, 원자 UPDATE 전환 후에는 0이어야 한다.
 */
const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    checkout: { executor: 'constant-vus', vus: 30, duration: __ENV.DURATION || '90s', exec: 'checkout' },
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    username: '1', password: __ENV.USER_PASSWORD || 'user-local-only',
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  check(res, { 'login 200': (r) => r.status === 200 });
  return { token: res.json('token') };
}

export function checkout(data) {
  const auth = `Bearer ${data.token}`;
  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: 1, quantity: 1 }],
  }), { headers: { 'Content-Type': 'application/json', Authorization: auth }, tags: { name: 'order' } });
  if (orderRes.status !== 201) { sleep(0.3); return; }

  const order = orderRes.json();
  const confirmRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `pc-${uuidv4()}`, orderNo: order.orderNo, amount: order.totalAmount, pointAmount: 0,
  }), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4(), Authorization: auth },
    tags: { name: 'confirm' },
  });
  check(confirmRes, { 'confirm 2xx': (r) => r.status === 200 || r.status === 202 });
  sleep(0.3);
}
