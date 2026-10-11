import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * R12.1 실 경로 확인 — 방송 특가 한정 수량에 동시 주문이 몰려도 확정 합계가 N을 넘지 않고,
 * 초과분은 결제 호출 없이 바로 거절되는지(R15 방향)를 실제 서버에 대고 확인한다.
 *
 * tools/run-live-order-stock.sh가 먼저 방송을 만들고 상품을 한정 수량 N으로 고정한 뒤,
 * 이 스크립트를 BROADCAST_ID·PRODUCT_ID와 함께 VUS(=N+α)명으로 돌린다. VU마다 서로 다른
 * 계정(= 서로 다른 멱등 키 공간)으로 정확히 1건씩 주문한다(spike-multi-account.js와 같은
 * "계정 풀 미리 만들기" 관례).
 *
 *   k6 run -e BASE_URL=http://localhost:8080 -e BROADCAST_ID=1 -e PRODUCT_ID=1 -e VUS=40 \
 *     k6/live-order-stock.js
 */
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const BROADCAST_ID = __ENV.BROADCAST_ID;
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const VUS = Number(__ENV.VUS || 40);

const confirmed = new Counter('live_order_confirmed');     // 201 — 확정 주문
const soldOut = new Counter('live_order_sold_out');         // 409 LIMITED_QUANTITY_SOLD_OUT — 게이트 거절
const rateLimited = new Counter('live_order_rate_limited'); // 429 — 유입 제어(이 실험의 대상이 아니다)
const other = new Counter('live_order_other');

export const options = {
  scenarios: {
    orders: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,          // VU마다 정확히 1건 — "각 1개씩 동시 주문"
      maxDuration: '90s',
    },
  },
};

// setup: 계정을 미리 만들어 토큰 풀을 짓는다(spike-multi-account.js와 같은 이유) — 가입/로그인도
// IP 기준 제한(5/s)을 타므로 간격을 둔다.
export function setup() {
  if (!BROADCAST_ID) {
    throw new Error('BROADCAST_ID가 필요합니다 — tools/run-live-order-stock.sh로 먼저 방송을 만들고 고정하세요.');
  }
  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < VUS; i++) {
    const email = `k6-live-order-${run}-${i}@load.test`;
    const password = 'k6-load-only-1234';
    const signup = http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'signup' } });
    check(signup, { 'signup 201': (r) => r.status === 201 });
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
    if (login.status === 200) tokens.push(login.json('token'));
    sleep(0.3);
  }
  if (tokens.length < VUS) {
    throw new Error(`계정 준비 실패: ${tokens.length}/${VUS}개만 로그인됨`);
  }
  console.log(`계정 ${tokens.length}개 준비 완료 — 방송 ${BROADCAST_ID}, 상품 ${PRODUCT_ID}에 동시 주문 시작`);
  return { tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const res = http.post(`${BASE}/api/v1/live/broadcasts/${BROADCAST_ID}/orders`,
    JSON.stringify({ productId: PRODUCT_ID }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        'Idempotency-Key': uuidv4(),
      },
      tags: { name: 'live-order' },
    });

  if (res.status === 201) {
    confirmed.add(1);
  } else if (res.status === 409 && String(res.body).includes('LIMITED_QUANTITY_SOLD_OUT')) {
    soldOut.add(1);
  } else if (res.status === 429) {
    rateLimited.add(1);
  } else {
    other.add(1);
  }
  check(res, { '201(확정) 또는 409(매진) 둘 중 하나': (r) => r.status === 201 || r.status === 409 });
}
