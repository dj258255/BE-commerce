import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 스파이크 테스트 — 폭주 트래픽에서 과부하 제어(429 shedding)가 성공 요청의 지연을 지키는지 본다.
 *
 * 두 번 돌려 비교한다:
 *   전: APP_RATELIMIT_ENABLED=false ./gradlew -p commerce bootRun  →  k6 run k6/spike-test.js
 *       (유입 제어 없음 — 폭주가 그대로 DB/스레드로 흘러 5xx·지연 폭발 예상)
 *   후: APP_RATELIMIT_ENABLED=true(기본)               →  k6 run k6/spike-test.js
 *       (초과분을 바깥 층에서 429로 싸게 쳐내되, 통과한 요청의 p95가 유지되는지가 핵심)
 *
 * 판정 기준(thresholds):
 *   - 429는 실패가 아니다 — 과부하 제어가 "일하는 중"이라는 신호(shed로 별도 집계).
 *   - 5xx만 실패로 센다(server_errors rate < 1%).
 *   - 성공(2xx) 요청의 p95 < 1500ms — 쳐내기 덕에 살아남은 요청은 빨라야 한다.
 *
 * 전제: docker compose up(MySQL/Redis) → bootRun, products/stock 시드(checkout-load.js와 동일).
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

// 5xx만 실패로 집계 — 429(shed)는 의도된 거절이므로 http_req_failed 대신 커스텀 지표를 쓴다.
const serverErrors = new Rate('server_errors');
const shedRate = new Rate('shed_rate');           // 429 비율(관찰용 — 임계치 없음)
const okDuration = new Trend('ok_duration', true); // 2xx 요청만의 응답시간

export const options = {
  scenarios: {
    // 스파이크: 0 → 150 VU를 10초 만에 급증시키고 30초 유지 후 급감.
    // "선착순 오픈 순간" 같은 계단형 폭주를 흉내낸다.
    spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 150 },  // 급증
        { duration: '30s', target: 150 },  // 폭주 유지
        { duration: '10s', target: 0 },    // 급감
      ],
    },
  },
  thresholds: {
    server_errors: ['rate<0.01'],          // 5xx < 1% — 폭주에도 서버가 깨지지 않아야 한다
    ok_duration: ['p(95)<1500'],           // 성공 요청의 p95 — shedding이 지켜야 하는 값
  },
};

// 응답을 ok(2xx) / shed(429) / 5xx로 분류해 집계한다.
function record(res) {
  const is5xx = res.status >= 500;
  const isShed = res.status === 429;
  const isOk = res.status >= 200 && res.status < 300;
  serverErrors.add(is5xx);
  shedRate.add(isShed);
  if (isOk) okDuration.add(res.timings.duration);
  return { isOk, isShed };
}

// setup: 로그인 1회로 토큰을 얻어 모든 VU가 재사용한다(요청당 BCrypt 없음).
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

  // 1) 주문 생성 — 유입 제어 1차 관문(429 예상 지점)
  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: 1, quantity: 1 }],
  }), {
    headers: { 'Content-Type': 'application/json', Authorization: auth },
    tags: { name: 'order' },
  });

  const order = record(orderRes);
  check(orderRes, {
    'order ok(2xx)': () => order.isOk,
    'order shed(429)': () => order.isShed,   // 실패가 아니라 과부하 제어의 정상 동작
  });
  if (!order.isOk) {
    sleep(0.3);   // 쳐내진 VU는 잠깐 물러났다 재시도(Retry-After: 1보다 짧게 계속 압박)
    return;
  }

  const body = orderRes.json();

  // 2) 결제 승인 — 고유 멱등키. 유입 제어 2차 관문.
  const confirmRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `spike-${uuidv4()}`,
    orderNo: body.orderNo,
    amount: body.totalAmount,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': uuidv4(),
      Authorization: auth,
    },
    tags: { name: 'confirm' },
  });

  const confirm = record(confirmRes);
  check(confirmRes, {
    'confirm ok(2xx)': () => confirm.isOk,
    'confirm shed(429)': () => confirm.isShed,
  });

  sleep(0.1);   // 스파이크답게 거의 쉬지 않고 압박
}
