import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 체크아웃 부하테스트 — 주문 생성 → 결제 승인 흐름을 실제 사용자 시나리오로 두들긴다.
 *
 * 전제:
 *   1. 앱 실행 (docker compose up 으로 MySQL/Redis 띄운 뒤 ./gradlew -p commerce bootRun)
 *   2. 상품/재고 시드: products(1, 'A', 10000), stock(1, 넉넉히)
 *   3. k6 run k6/checkout-load.js
 *
 * 성능 측정 시 rate limiter를 끄고 돌린다:
 *   APP_RATELIMIT_ENABLED=false ./gradlew -p commerce bootRun
 * 이 스크립트는 데모 유저 1명이 주문→승인을 반복하므로, rate limiter(기본 on, per-user 5/s)에
 * 걸려 429가 섞이면 처리량·지연 측정이 왜곡된다. 순수 처리 성능을 보려면 rate limit을 끈다.
 * (반대로 spike-test는 rate limit을 켠 채 돌려 폭주의 몇 %를 429로 쳐내는지=shed를 측정한다.)
 *
 * FakePgClient(비-prod)가 승인을 성공 처리하므로 실제 PG 키 없이 부하를 준다.
 * thresholds를 위반하면(예: p95 > 1500ms) k6가 실패로 종료 → 성능 회귀를 CI에서 잡는다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    // 목표 산정 예: "선착순 이벤트로 순간 트래픽 급증" → 단계적으로 VU를 올린다
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 20 },
        { duration: '40s', target: 50 },
        { duration: '20s', target: 0 },
      ],
    },
  },
  // 인증은 로그인 1회로 JWT를 발급받고, 이후 요청은 대칭키 서명 검증만 한다(요청당 BCrypt 제거).
  // 무상태 HTTP Basic이 요청마다 비밀번호를 BCrypt로 재검증하던 병목(min ~110ms)을 없앤 뒤의 값.
  // 임계치는 회귀 안전망으로 유지한다.
  thresholds: {
    http_req_failed: ['rate<0.01'],            // 오류율 1% 미만
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
  },
};

// setup: 로그인 1회로 토큰을 얻어 모든 VU가 재사용한다(주문/결제 요청당 BCrypt 없음).
// 데모 유저 "1"(=userId). userId는 클라이언트가 아니라 토큰 subject(=principal)에서 얻는다.
export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    username: '1',
    password: __ENV.USER_PASSWORD || 'user-local-only',
  }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'login' },
  });
  check(res, { 'login 200': (r) => r.status === 200 });
  return { token: res.json('token') };
}

export default function (data) {
  const auth = `Bearer ${data.token}`;

  // 1) 주문 생성 — 클라이언트는 productId·quantity만 보낸다(가격·소유자는 서버 권위)
  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: 1, quantity: 1 }],
  }), {
    headers: { 'Content-Type': 'application/json', Authorization: auth },
    tags: { name: 'order' },
  });

  check(orderRes, { 'order 201': (r) => r.status === 201 });
  if (orderRes.status !== 201) return;

  const order = orderRes.json();

  // 2) 결제 승인 — 멱등키 필수, 금액은 서버가 준 totalAmount 그대로
  const confirmRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `load-${uuidv4()}`,
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

  check(confirmRes, { 'confirm 2xx': (r) => r.status === 200 || r.status === 202 });

  sleep(1);
}
