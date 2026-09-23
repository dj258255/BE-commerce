import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 실험 1: 정산 배치 ↔ 결제 API 경합 실측 (ROADMAP-MSA.local.md)
 *
 * 가설: 같은 JVM·같은 HikariCP 풀을 쓰므로, 정산 배치(항목 2만 건 SELECT + 전건 UPDATE를
 * 한 트랜잭션에 커밋)가 도는 동안 결제 p95가 오염된다.
 *
 * 구성: 시나리오 2개가 한 스크립트에서 시간차로 돈다.
 *   checkout           0s~4m    상시 체크아웃 부하(측정 대상). 0~90s가 베이스라인.
 *   settlement_trigger 90s~     어드민 API로 시드된 날짜(2001-01-01~10)의 배치를
 *                               12초 간격으로 연속 트리거 → 90s 이후가 경합 구간.
 *
 * 부하 모델: VU마다 독립 회원을 가입시켜 쓴다(setup에서 30명 가입·로그인). 데모 유저 1명으로
 * 돌리면 포인트 적립(실결제액 1%)이 같은 PointAccount 행을 동시 갱신해 낙관적 락 충돌로
 * confirm이 500으로 무너진다(1차 실행에서 실측 — 운영에선 per-user rate limit 5/s가 막아주는
 * 동시성이라, limiter를 끈 단일 유저 부하는 비현실적 모델이다). 실사용 트래픽 = 다수 사용자.
 *
 * 전제:
 *   1. docker compose up -d && APP_RATELIMIT_ENABLED=false ./gradlew -p commerce bootRun
 *      (limiter는 배치 경합만 남기고 유입 제어 변수를 제거하기 위해 끈다)
 *   2. 시드: docker compose exec -T mysql mysql -ubecommerce -pbecommerce becommerce < k6/seed-settlement-contention.sql
 *   3. 상품/재고 시드: products(1), stock(1) — checkout-load.js와 동일 전제
 *   4. (그래프용) docker compose --profile monitoring up -d prometheus grafana
 *
 * 실행: k6 run k6/settlement-contention.js
 *
 * 판독: 결과는 k6 요약이 아니라 Grafana 시계열로 본다 — p95 패널과 HikariCP 패널에서
 * 90s 경계 전후를 비교한다. p95가 튀는 동시에 HikariCP pending이 같이 튀면
 * "커넥션 풀 경합" 인과까지 증명된다. k6 요약의 {scenario:checkout} 분위수는
 * 전 구간 평균이라 보조 지표로만 쓴다.
 *
 * 재실행: 배치는 날짜당 멱등(settlements 유니크)이라, 다시 돌리려면 시드 SQL을
 * 재실행해 settlements를 지우고 항목을 CONFIRMED로 되돌린다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

// 시드 SQL과 계약: 2001-01-01부터 10개 날짜
const SEED_DATES = Array.from({ length: 10 }, (_, i) =>
  `2001-01-${String(i + 1).padStart(2, '0')}`);

export const options = {
  scenarios: {
    // 측정 대상: 상시 체크아웃 부하. 스파이크가 아니라 고정 VU로 평평하게 깐다 —
    // 부하 변동이 없어야 90s 이후의 변화를 배치 탓으로 귀속할 수 있다.
    checkout: {
      executor: 'constant-vus',
      vus: 30,
      duration: '4m',
      exec: 'checkout',
    },
    // 교란 변수: 90s부터 배치를 연속 트리거. 1 VU면 충분하다(배치는 서버에서 돈다).
    settlement_trigger: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: SEED_DATES.length,
      startTime: '90s',
      maxDuration: '150s',
      exec: 'triggerSettlement',
    },
  },
  thresholds: {
    // checkout만 회귀 감시(배치 트리거 요청은 오래 걸리는 게 정상이라 제외)
    'http_req_failed{scenario:checkout}': ['rate<0.01'],
    'http_req_duration{scenario:checkout}': ['p(95)<1500', 'p(99)<3000'],
  },
};

const VUS = 30;

// setup: VU 수만큼 회원을 가입·로그인해 토큰 풀을 만든다(+ 배치 트리거용 admin).
// 이메일에 런 타임스탬프를 붙여 재실행 시 가입 충돌(중복 이메일)을 피한다.
export function setup() {
  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < VUS; i++) {
    const email = `k6-contention-${run}-${i}@load.test`;
    const password = 'k6-load-only-1234';

    const signup = http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({
      email, password,
    }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'signup' } });
    check(signup, { 'signup 201': (r) => r.status === 201 });

    // 복합 UserDetailsService가 이메일→회원을 찾고, JWT subject는 숫자 회원 id다
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
      username: email, password,
    }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
    check(login, { 'user login 200': (r) => r.status === 200 });
    tokens.push(login.json('token'));
  }

  const admin = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    username: 'admin',
    password: __ENV.ADMIN_PASSWORD || 'admin-local-only',
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  check(admin, { 'admin login 200': (r) => r.status === 200 });

  return { tokens, adminToken: admin.json('token') };
}

// checkout-load.js와 동일한 주문 생성 → 결제 승인 흐름 (측정 대상).
// VU마다 자기 회원 토큰을 쓴다 — 포인트 계좌·주문 소유가 분산돼 단일 행 경합이 없다.
export function checkout(data) {
  const auth = `Bearer ${data.tokens[(__VU - 1) % data.tokens.length]}`;

  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: 1, quantity: 1 }],
  }), {
    headers: { 'Content-Type': 'application/json', Authorization: auth },
    tags: { name: 'order' },
  });
  check(orderRes, { 'order 201': (r) => r.status === 201 });
  if (orderRes.status !== 201) return;

  const order = orderRes.json();

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

// 시드된 날짜의 정산 배치를 순차 트리거한다. 12초 간격 — 배치 실행(수 초)과
// 다음 트리거 사이에 짧은 회복 구간을 둬서 그래프에 톱니(경합↔회복)가 드러나게 한다.
export function triggerSettlement(data) {
  const date = SEED_DATES[__ITER];
  const res = http.post(
    `${BASE}/api/v1/admin/settlements/run?date=${date}`, null, {
      headers: { Authorization: `Bearer ${data.adminToken}` },
      tags: { name: 'settlement-run' },
      timeout: '120s', // 대량 항목 배치는 오래 걸리는 게 정상
    });
  // 200 = 정산 생성, 204 = 재실행/대상 없음(시드 누락 신호이므로 체크로 드러낸다)
  check(res, { 'settlement run 200': (r) => r.status === 200 });
  console.log(`[settlement] date=${date} status=${res.status} elapsed=${res.timings.duration}ms`);

  sleep(12);
}
