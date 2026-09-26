import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 한정 상품 매진 부하(#374). 재고를 언제 잡는지(app.stock.reservation)에 따라 망취소와 팔지 못한 재고가 어떻게 갈리는지 잰다.
 *
 * 주문 하나마다:
 *   1) 주문 생성. 409 면 주문 단계 매진(CHECK · AT_ORDER)
 *   2) ABANDON 확률로 결제하지 않고 떠난다(이탈)
 *   3) 결제 확정. UNKNOWN 확률로 결과 모름을 만든다. 그중 절반은 PG 에 승인으로 남고(unk-ok-) 절반은 PG 에 없다(unk-lost-)
 *
 * 최종 판정은 DB 에서 한다(tools/stock_reservation_eval.py). 여기 카운터는 응답이 무엇이었는지만 센다.
 *
 *   k6 run -e BASE_URL=http://localhost:18091 -e PRODUCT_ID=90374 k6/flash-sale-stock.js
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 90374);
const RATE = Number(__ENV.RATE || 5);
const DURATION = __ENV.DURATION || '60s';
const ABANDON = Number(__ENV.ABANDON || 0.2);
const UNKNOWN = Number(__ENV.UNKNOWN || 0.1);

const orderCreated = new Counter('flash_order_created');
const orderSoldOut = new Counter('flash_order_sold_out');
const abandoned = new Counter('flash_abandoned');
const confirmPaid = new Counter('flash_confirm_paid');
const confirmUnknown = new Counter('flash_confirm_unknown');
const confirmNetCancelled = new Counter('flash_confirm_net_cancelled');
const confirmSoldOut = new Counter('flash_confirm_sold_out');
const confirmOther = new Counter('flash_confirm_other');

export const options = {
  scenarios: {
    sale: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 40,
      maxVUs: 120,
    },
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    username: '1',
    password: __ENV.USER_PASSWORD || 'user-local-only',
  }), { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'login 200': (r) => r.status === 200 });
  return { token: res.json('token') };
}

export default function (data) {
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` };

  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: PRODUCT_ID, quantity: 1 }],
  }), { headers, tags: { name: 'order' } });
  if (orderRes.status === 409) {
    orderSoldOut.add(1);
    return;
  }
  if (orderRes.status !== 201) {
    confirmOther.add(1);
    return;
  }
  orderCreated.add(1);
  if (Math.random() < ABANDON) {
    abandoned.add(1);
    return;
  }

  const order = orderRes.json();
  const r = Math.random();
  const prefix = r < UNKNOWN / 2 ? 'unk-ok-' : (r < UNKNOWN ? 'unk-lost-' : 'pk-');
  const confirmRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `${prefix}${uuidv4()}`,
    orderNo: order.orderNo,
    amount: order.totalAmount,
  }), { headers: { ...headers, 'Idempotency-Key': uuidv4() }, tags: { name: 'confirm' } });

  if (confirmRes.status === 202) {
    confirmUnknown.add(1);
  } else if (confirmRes.status === 409 && String(confirmRes.body).includes('OUT_OF_STOCK')) {
    confirmSoldOut.add(1);
  } else if (confirmRes.status === 200 && confirmRes.json('orderStatus') === 'PAID') {
    confirmPaid.add(1);
  } else if (confirmRes.status === 200 && confirmRes.json('orderStatus') === 'FAILED') {
    confirmNetCancelled.add(1);
  } else {
    confirmOther.add(1);
  }
}
