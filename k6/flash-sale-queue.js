import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 한정 상품 판매 시작 순간의 대기열(#383). 구매자마다 다른 계정으로, 짧은 시간에 몰려 도착한다.
 *
 * 구매자 하나(도착 번호 idx)마다:
 *   1) QUEUE=1 이면 줄에 서고 1초마다 순번을 묻는다. MAX_WAIT 초가 지나면 포기
 *   2) 이탈자(idx % 5 == 4, 20%)는 입장 뒤 주문하지 않고 떠난다. 줄에서 나가지 않는다(브라우저를 닫은 사람)
 *   3) 나머지는 THINK 초 안에서 도착 번호로 정한 시간만큼 고민한 뒤 주문한다(주소 · 옵션 입력). 입장 칸 만료보다
 *      오래 고민하면 칸을 잃고 주문이 429 QUEUE_PASS_REQUIRED 로 막힌다(lease_lost)
 *   4) 주문 생성 → 결제 확정. 결과 모름은 idx % 20 == 3(PG 에 승인) · == 13(PG 에 없음), 약 10%
 *   5) 결제가 끝나면(성공 · 실패) 줄에서 나간다
 *
 * 이탈과 결과 모름을 도착 번호로 정해 조건마다 같은 사람이 같은 행동을 하게 했다. 공정성을 조건끼리 비교하기 위해서다.
 * 한 사람의 결과는 "RESULT {json}" 한 줄로 남기고 최종 판정(주문이 결국 PAID 인가)은 DB 에서 한다.
 *
 *   k6 run -e BASE_URL=http://localhost:18091 -e QUEUE=1 k6/flash-sale-queue.js
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 90374);
const BUYERS = Number(__ENV.BUYERS || 300);
const RATE = Number(__ENV.RATE || 100);
const QUEUE = __ENV.QUEUE === '1';
const EVENT = __ENV.EVENT || 'drop';
const MAX_WAIT = Number(__ENV.MAX_WAIT || 180);
const RUN = __ENV.RUN || String(Date.now());
const THINK = Number(__ENV.THINK || 0);

export const options = {
  setupTimeout: '20m',
  scenarios: {
    sale: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${Math.ceil(BUYERS / RATE)}s`,
      preAllocatedVUs: BUYERS,
      maxVUs: BUYERS,
      gracefulStop: `${MAX_WAIT + 120}s`,
    },
  },
};

const json = { 'Content-Type': 'application/json' };

export function setup() {
  const tokens = [];
  for (let i = 0; i < BUYERS; i++) {
    const email = `k6-flash-${RUN}-${i}@load.test`;
    const password = 'k6-load-only-1234';
    http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }), { headers: json });
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }), { headers: json });
    if (login.status !== 200) {
      throw new Error(`로그인 실패 idx=${i} status=${login.status}`);   // 계정이 모자라면 실험이 성립하지 않는다
    }
    tokens.push(login.json('token'));
  }
  return { tokens };
}

function log(result) {
  console.log('RESULT ' + JSON.stringify(result));
}

export default function (data) {
  const idx = exec.scenario.iterationInTest;
  if (idx >= data.tokens.length) {
    return;
  }
  const headers = { ...json, Authorization: `Bearer ${data.tokens[idx]}` };
  const arrivedAt = Date.now();
  const result = { idx, queue: QUEUE, waitMs: 0 };

  if (QUEUE) {
    let admitted = false;
    let res = http.post(`${BASE}/api/v1/queue/${EVENT}/enter`, null, { headers, tags: { name: 'enter' } });
    while (true) {
      if (res.status === 200 && res.json('admitted') === true) {
        admitted = true;
        break;
      }
      if (Date.now() - arrivedAt > MAX_WAIT * 1000) {
        break;
      }
      sleep(1);
      res = http.get(`${BASE}/api/v1/queue/${EVENT}/status`, { headers, tags: { name: 'status' } });
    }
    result.waitMs = Date.now() - arrivedAt;
    if (!admitted) {
      result.outcome = 'gave_up';
      log(result);
      return;
    }
  }

  if (idx % 5 === 4) {
    result.outcome = 'abandoned';   // 줄에서 나가지 않고 떠난다
    log(result);
    return;
  }

  const think = THINK > 0 ? (idx * 7) % (THINK + 1) : 0;   // 0 ~ THINK 초를 고르게, 조건끼리 같은 사람은 같은 시간
  result.thinkS = think;
  if (think > 0) sleep(think);

  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({ items: [{ productId: PRODUCT_ID, quantity: 1 }] }),
    { headers, tags: { name: 'order' } });
  if (orderRes.status !== 201) {
    if (orderRes.status === 409) {
      result.outcome = 'sold_out_at_order';
    } else if (orderRes.status === 429 && String(orderRes.body).includes('QUEUE_PASS_REQUIRED')) {
      result.outcome = 'lease_lost';
    } else {
      result.outcome = `order_${orderRes.status}`;
    }
    log(result);
    if (QUEUE) http.post(`${BASE}/api/v1/queue/${EVENT}/leave`, null, { headers });
    return;
  }
  const order = orderRes.json();
  result.orderNo = order.orderNo;

  const prefix = idx % 20 === 3 ? 'unk-ok-' : (idx % 20 === 13 ? 'unk-lost-' : 'pk-');
  const confirmRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `${prefix}${uuidv4()}`, orderNo: order.orderNo, amount: order.totalAmount,
  }), { headers: { ...headers, 'Idempotency-Key': uuidv4() }, tags: { name: 'confirm' } });

  if (confirmRes.status === 202) {
    result.outcome = 'unknown';
  } else if (confirmRes.status === 409 && String(confirmRes.body).includes('OUT_OF_STOCK')) {
    result.outcome = 'sold_out_at_payment';
  } else if (confirmRes.status === 200 && confirmRes.json('orderStatus') === 'PAID') {
    result.outcome = 'paid';
  } else {
    result.outcome = `confirm_${confirmRes.status}`;
  }
  result.doneMs = Date.now() - arrivedAt;
  log(result);
  if (QUEUE) http.post(`${BASE}/api/v1/queue/${EVENT}/leave`, null, { headers });
}
