import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 결제 화면(static/checkout.html)과 똑같이 움직이는 고객으로 데드라인을 잰다(#440 후속).
 *
 * k6/pg-brownout-deadline.js 는 요청의 30%에 "남은 시간 0"을 실어 이미 떠난 고객을 흉내냈다. 실제 화면은
 * 그런 값을 보내지 않는다. 화면이 하는 일은 셋이다.
 *   1) 승인 요청에 X-Request-Timeout-Ms(응답 대기 15초 + 조회 창 12초)를 싣는다
 *   2) 15초 안에 응답이 없으면 기다림을 멈춘다(AbortController)
 *   3) 202 거나 기다림을 멈췄으면 1.2초 간격으로 10번까지 주문을 조회해 결과를 확인한다
 * 이 스크립트는 그 셋을 그대로 한다. 고객은 한 번만 시도한다(다시 누르지 않는다).
 *
 * 조건이 섰다는 증거: 요청의 CONTROL_FRACTION 에는 남은 시간 0 을 싣는다(paymentKey 접두어 ctrl-).
 * 데드라인을 켠 실행에서 생략 카운터가 이 수와 같으면 확인 자체는 작동한 것이다. 화면 고객(screen-)의
 * 생략이 0 이면 "작동은 하지만 화면 경로에서는 설 일이 없다"로 읽을 수 있다.
 *
 * 사용: k6 run -e BASE_URL=... -e RATE=50 -e DURATION=60s k6/checkout-screen.js
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const SCREEN_TIMEOUT_MS = Number(__ENV.SCREEN_TIMEOUT_MS || 15000);   // checkout.html CONFIRM_TIMEOUT_MS
const POLL_INTERVAL_S = 1.2;                                          // checkout.html pollUntilDecided
const POLL_TRIES = 10;
// checkout.html CONFIRM_DEADLINE_MS = 응답 대기 + 조회 창. 2026-09-30 측정은 이 값을 도입하기 전이라
// 15000 을 보냈다(재현: -e SCREEN_DEADLINE_MS=15000). 값이 커지면 생략은 늘 수 없다.
const SCREEN_DEADLINE_MS = Number(__ENV.SCREEN_DEADLINE_MS || (SCREEN_TIMEOUT_MS + POLL_INTERVAL_S * 1000 * POLL_TRIES));
const CONTROL_FRACTION = Number(__ENV.CONTROL_FRACTION || 0.02);
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 2);

// 화면 고객이 처음 받은 응답
const firstOk = new Counter('screen_first_ok_200');
const firstPending = new Counter('screen_first_pending_202');
const firstRejected = new Counter('screen_first_rejected_4xx');
const firstServerError = new Counter('screen_first_5xx');
const clientTimeout = new Counter('screen_client_timeout');          // 15초에 기다림을 멈춤
// 조회로 확인한 최종 결과(202 또는 기다림을 멈춘 고객만)
const pollDone = new Counter('screen_poll_done');
const pollAborted = new Counter('screen_poll_aborted');
const pollUndecided = new Counter('screen_poll_undecided');          // 10번 조회 안에 확정 안 됨
const timeoutThenDone = new Counter('screen_timeout_then_done');     // 기다림을 멈췄는데 결제는 완료
const timeoutThenAborted = new Counter('screen_timeout_then_aborted');
const confirmMs = new Trend('screen_confirm_ms', true);              // 화면이 기다린 시간(멈추면 15초)
const screenSent = new Counter('screen_sent');
const controlSent = new Counter('control_sent');

export const options = {
  scenarios: {
    arrival: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.VUS || 300),
      maxVUs: Number(__ENV.MAX_VUS || 2500),
      gracefulStop: '120s',    // 15초 기다림 + 12초 조회. 워커가 마른 조건에서는 조회도 줄을 서서 넉넉히 둔다
    },
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

function pollUntilDecided(auth, orderNo) {
  for (let i = 0; i < POLL_TRIES; i++) {
    sleep(POLL_INTERVAL_S);
    const r = http.get(`${BASE}/api/v1/orders/${orderNo}`, {
      headers: { Authorization: auth },
      tags: { name: 'poll' },
    });
    if (r.status === 200) {
      const ps = r.json('paymentStatus');
      if (ps === 'DONE' || ps === 'ABORTED' || ps === 'CANCELED' || ps === 'PARTIAL_CANCELED') return ps;
    }
  }
  return null;
}

export default function (data) {
  const auth = `Bearer ${data.token}`;
  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: PRODUCT_ID, quantity: 1 }],
  }), {
    headers: { 'Content-Type': 'application/json', Authorization: auth },
    tags: { name: 'order' },
  });
  if (orderRes.status !== 201) {
    return;
  }
  const order = orderRes.json();

  const isControl = Math.random() < CONTROL_FRACTION;
  const started = Date.now();
  const res = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `${isControl ? 'ctrl' : 'screen'}-${uuidv4()}`,
    orderNo: order.orderNo,
    amount: order.totalAmount,
    installmentMonths: 0,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': uuidv4(),
      Authorization: auth,
      'X-Request-Timeout-Ms': String(isControl ? 0 : SCREEN_DEADLINE_MS),
    },
    timeout: `${SCREEN_TIMEOUT_MS}ms`,
    tags: { name: 'confirm' },
  });
  if (isControl) {
    controlSent.add(1);
    return;
  }
  screenSent.add(1);
  confirmMs.add(Date.now() - started);

  const timedOut = res.status === 0;
  if (timedOut) clientTimeout.add(1);
  else if (res.status === 200) { firstOk.add(1); return; }
  else if (res.status === 202) firstPending.add(1);
  else if (res.status >= 500) { firstServerError.add(1); return; }
  else { firstRejected.add(1); return; }

  const decided = pollUntilDecided(auth, order.orderNo);
  if (decided === 'DONE') { pollDone.add(1); if (timedOut) timeoutThenDone.add(1); }
  else if (decided === 'ABORTED') { pollAborted.add(1); if (timedOut) timeoutThenAborted.add(1); }
  else pollUndecided.add(1);
}
