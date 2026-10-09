import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * R15 — 한정 수량 LIMIT개(기본 50) 상품에 VUS명(기본 1,000)이 동시에 1개씩 주문했을 때,
 * 확정은 정확히 LIMIT건만 되고 나머지(VUS-LIMIT건)는 결제를 거치지 않고(R12.2) 빠르게
 * 거절되는지(거절 응답 p95 200ms 미만)를 실제 서버에 대고 확인한다.
 *
 * 샌드박스에는 k6가 없어 이 스크립트는 호스트에서 돌린다. 실행 순서(자세한 내용은
 * docs/performance/live-order-r15.md):
 *   1) ./tools/prepare-live-order-broadcast.sh 로 방송을 만들고 상품을 LIMIT개로 고정
 *      → 표준출력의 BROADCAST_ID 값을 받는다.
 *   2) ./tools/prepare-live-order-viewers.sh 로 시청자 VUS명의 토큰을 TOKENS_FILE(기본
 *      /tmp, 저장소 밖)에 미리 저장한다(계정 가입은 IP 제한 때문에 느려서 — 측정 구간에
 *      섞이지 않게 미리 끝낸다).
 *   3) k6 run -e BASE_URL=http://<host>:8080 -e BROADCAST_ID=<1에서 받은 값> \
 *        -e PRODUCT_ID=1 -e LIMIT=50 -e VUS=1000 \
 *        -e TOKENS_FILE=/tmp/live-order-viewers-tokens.json \
 *        k6/live-order-flash-sale.js
 */
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const BROADCAST_ID = __ENV.BROADCAST_ID;
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const LIMIT = Number(__ENV.LIMIT || 50);
const VUS = Number(__ENV.VUS || 1000);
const TOKENS_FILE = __ENV.TOKENS_FILE || '/tmp/live-order-viewers-tokens.json';
const EXPECTED_REJECTED = VUS - LIMIT;

const tokens = JSON.parse(open(TOKENS_FILE));

const confirmed = new Counter('live_order_confirmed');       // 201 — 확정 주문
const soldOut = new Counter('live_order_sold_out');           // 409 LIMITED_QUANTITY_SOLD_OUT — 거절
const rateLimited = new Counter('live_order_rate_limited');   // 429 — 유입 제어(이 실험의 대상이 아니다)
const other = new Counter('live_order_other');
// 확정 분기 안에서만 늘어난다 — 거절(409) 분기는 코드상 이 호출에 절대 도달하지 않는다.
// 그게 "거절 경로의 결제 호출 수 0"의 증거다(아래 handleSummary가 숫자로도 보여준다).
const paymentCalls = new Counter('live_order_payment_calls');
// R15 NFR "거절 응답만 필터해 p95 집계" — 이 Trend는 409(매진 거절) 응답에만 샘플을 쌓는다.
const rejectedDurationMs = new Trend('live_order_rejected_duration_ms');

export const options = {
  scenarios: {
    orders: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1, // VU마다 정확히 1건 — "각 1개씩 동시 주문"
      maxDuration: '60s',
    },
  },
  thresholds: {
    live_order_confirmed: [`count==${LIMIT}`],
    live_order_sold_out: [`count==${EXPECTED_REJECTED}`],
    live_order_rejected_duration_ms: ['p(95)<200'],
  },
};

export function setup() {
  if (!BROADCAST_ID) {
    throw new Error('BROADCAST_ID가 필요합니다 — tools/prepare-live-order-broadcast.sh로 먼저 방송을 만들고 고정하세요.');
  }
  if (tokens.length < VUS) {
    throw new Error(`토큰이 부족합니다(${tokens.length}/${VUS}) — tools/prepare-live-order-viewers.sh로 VUS만큼 준비하세요.`);
  }
  console.log(`시청자 토큰 ${tokens.length}개 확인 — 방송 ${BROADCAST_ID}, 상품 ${PRODUCT_ID}(한정 ${LIMIT}개)에 ${VUS}명 동시 주문 시작`);
}

export default function () {
  const token = tokens[(__VU - 1) % tokens.length];
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
    // R11 흐름을 끝까지 닫는다(결제 승인). 거절 분기는 이 코드에 도달하지 않는다.
    const orderNo = res.json('orderNo');
    const totalAmount = res.json('totalAmount');
    const payRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
      paymentKey: `k6-r15-${orderNo}-${uuidv4()}`,
      orderNo,
      amount: totalAmount,
      pointAmount: 0,
      walletAmount: 0,
      installmentMonths: 0,
    }), {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      tags: { name: 'live-order-payment' },
    });
    paymentCalls.add(1);
    check(payRes, { '결제 승인 200': (r) => r.status === 200 });
  } else if (res.status === 409 && String(res.body).includes('LIMITED_QUANTITY_SOLD_OUT')) {
    soldOut.add(1);
    rejectedDurationMs.add(res.timings.duration);
  } else if (res.status === 429) {
    rateLimited.add(1);
  } else {
    other.add(1);
  }
  check(res, { '201(확정) 또는 409(매진) 둘 중 하나': (r) => r.status === 201 || r.status === 409 });
}

export function handleSummary(data) {
  const confirmedCount = data.metrics.live_order_confirmed?.values?.count ?? 0;
  const soldOutCount = data.metrics.live_order_sold_out?.values?.count ?? 0;
  const paymentCallCount = data.metrics.live_order_payment_calls?.values?.count ?? 0;
  const rejectedP95 = data.metrics.live_order_rejected_duration_ms?.values?.['p(95)'];

  const lines = [
    `=== R15 한정 ${LIMIT}개/${VUS}명 동시 주문 결과 ===`,
    `확정: ${confirmedCount} (기대 ${LIMIT})`,
    `거절(매진): ${soldOutCount} (기대 ${EXPECTED_REJECTED})`,
    `거절 응답 p95: ${rejectedP95 != null ? rejectedP95.toFixed(1) : '측정 안 됨'}ms (기준 200ms 미만)`,
    `결제 호출 수: ${paymentCallCount} — 확정(${confirmedCount})건과 같아야 한다(거절 분기는 결제 호출에 도달하지 않는다)`,
  ];
  console.log(lines.join('\n'));
  return {
    stdout: lines.join('\n') + '\n',
    'docs/performance/live-order-r15-summary.json': JSON.stringify(data, null, 2),
  };
}
