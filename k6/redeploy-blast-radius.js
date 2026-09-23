import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 실험 3: 배포 결합(장애 반경) 실측 (docs/performance/msa-baseline-experiments.md)
 *
 * 가설: 모놀리스에서는 알림·정산처럼 결제와 무관한 모듈의 배포도 전체 재시작을
 * 요구하므로, 단일 인스턴스 기준 결제 전면 중단이 발생한다.
 *
 * 방법: 체크아웃 30VU를 3분간 상시로 깔고, 60초 시점에 운영자가 앱을 재시작한다
 * (알림 모듈 한 줄 수정을 배포하는 상황의 재현). 스크립트는 실패를 타임스탬프와
 * 함께 로깅해 다운타임 윈도를 남긴다.
 *
 * 전제: 실험 1과 동일(compose 기동, APP_RATELIMIT_ENABLED=false bootRun).
 * 재시작은 이 스크립트가 아니라 셸에서 수행한다:
 *   sleep 60 && kill <PID> && APP_RATELIMIT_ENABLED=false ./gradlew -p commerce bootRun
 *
 * 판독: 실패 로그의 최초~최후 타임스탬프 = 다운타임 윈도. 재기동 직후 구간의
 * p95(워밍업 스파이크)는 Prometheus로 본다. graceful shutdown(20s 드레이닝)이
 * 인플라이트 요청을 지키는지도 실패 유형(connection refused vs 5xx)으로 구분한다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
// VUS·DURATION은 env로 조절한다 — 재배포 실험은 기본값, HPA 스케일아웃 실험은 VUS를 키운다.
const VUS = Number(__ENV.VUS || 30);
const DURATION = __ENV.DURATION || '3m';

export const options = {
  scenarios: {
    checkout: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'checkout' },
  },
  // 재시작 실험이므로 실패는 예상 결과다. 임계치는 걸지 않고 관측만 한다.
};

export function setup() {
  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < VUS; i++) {
    const email = `k6-blast-${run}-${i}@load.test`;
    const password = 'k6-load-only-1234';
    http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'signup' } });
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
    check(login, { 'user login 200': (r) => r.status === 200 });
    tokens.push(login.json('token'));
  }
  return { tokens };
}

function logFailure(kind, res) {
  // status 0 = 커넥션 자체가 안 됨(프로세스 다운), 5xx = 서버가 받고 실패
  console.log(`[FAIL] ${new Date().toISOString()} ${kind} status=${res.status} err=${res.error || ''}`);
}

export function checkout(data) {
  const auth = `Bearer ${data.tokens[(__VU - 1) % data.tokens.length]}`;

  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: 1, quantity: 1 }],
  }), { headers: { 'Content-Type': 'application/json', Authorization: auth }, tags: { name: 'order' } });
  if (orderRes.status !== 201) { logFailure('order', orderRes); sleep(1); return; }

  const order = orderRes.json();
  const confirmRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `load-${uuidv4()}`,
    orderNo: order.orderNo,
    amount: order.totalAmount,
  }), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4(), Authorization: auth },
    tags: { name: 'confirm' },
  });
  if (confirmRes.status !== 200 && confirmRes.status !== 202) logFailure('confirm', confirmRes);

  sleep(1);
}
