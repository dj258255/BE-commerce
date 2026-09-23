import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 스파이크 테스트 (다계정) — 유입 제어의 <b>전역 층</b>이 실제로 일하는지 본다.
 *
 * 왜 spike-test.js로는 부족했나 — 산수 때문이다.
 *   spike-test.js는 계정 1개로 150 VU를 던진다. per-user 한도가 5/s이므로, 그 한 계정은
 *   무슨 짓을 해도 초당 5건까지만 통과한다. 경로 2개(orders·confirm)를 합쳐도 10/s다.
 *   전역 상한은 100/s다. 즉 <b>전역 층까지 요청이 도달한 적이 없다.</b> 층을 둘 만들어놓고
 *   바깥 층은 한 번도 부하를 받지 않은 채 "유입 제어를 검증했다"고 말해온 셈이다.
 *   (1차 실행 실측: DB 도달량 10.2 req/s ≈ 5/s × 2경로 — per-user 한 층만이 병목이었다.)
 *
 *   전역 층을 깨우려면 계정 수 × per-user > global 이어야 한다: N × 5 > 100 → N ≥ 21.
 *   여기서는 여유를 둬 40계정을 쓴다(제공량 200/s, 상한의 2배 → 전역 층이 확실히 지배적).
 *
 * 그래서 이 스크립트가 답하는 질문은 하나다:
 *   <b>"전역 상한이 걸리는 상태에서도, 통과한 요청의 지연이 지켜지는가?"</b>
 *   지켜지지 않으면 전역 상한값(100/s)이 서버 실제 처리량보다 높게 잡힌 것이다 — 즉
 *   상한이 보호막 노릇을 못 하고 있다는 뜻이고, 그건 실측 없이는 알 수 없다.
 *
 * 층 귀속: 429 응답의 X-RateLimit-Scope 헤더로 어느 층이 잘랐는지 센다(RateLimitFilter).
 *   shed_user   — 그 계정이 혼자 과하게 쳤다(정상: VU가 계정 수보다 많으면 당연히 발생)
 *   shed_global — 시스템 전체가 상한에 닿았다(이 실험이 보려는 것)
 *   shed_global이 0이면 실험이 성립하지 않은 것이다 — 계정 수나 VU를 늘려야 한다.
 *
 * 판정(thresholds):
 *   - 429는 실패가 아니다. 5xx만 실패로 센다(server_errors < 1%).
 *   - 통과한 요청의 p95 < 1500ms — 쳐내기가 지켜야 하는 값.
 *   - shed_global > 0 — 이게 0이면 위 두 지표가 녹색이어도 아무것도 증명하지 못한다.
 *
 * 전제:
 *   1. docker compose up -d && ./gradlew -p commerce bootRun   (limiter는 켠 채로 — 이 실험의 대상이다)
 *   2. 상품/재고 시드: products(1), stock(1) — checkout-load.js와 동일
 *
 * 실행: k6 run k6/spike-multi-account.js
 *
 * 비교 대조군(선택): APP_RATELIMIT_ENABLED=false로 한 번 더 돌린다. 제어가 없으면 200/s가
 * 그대로 DB로 흘러 p95·5xx가 어떻게 되는지가 "유입 제어가 벌어준 것"의 크기다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

// 전역 상한(기본 100/s)을 넘기려면 계정 수 × per-user(5/s) > 100 이어야 한다. 40 × 5 = 200/s.
const ACCOUNTS = Number(__ENV.ACCOUNTS || 40);
const VUS = Number(__ENV.VUS || 200);

const serverErrors = new Rate('server_errors');     // 5xx만 — 실패 판정 기준
const okDuration = new Trend('ok_duration', true);  // 2xx 요청만의 응답시간
const shedUser = new Counter('shed_user');          // per-user 층이 자른 수
const shedGlobal = new Counter('shed_global');      // 전역 층이 자른 수 ← 이 실험의 핵심
const shedUnknown = new Counter('shed_unknown');    // 헤더 없는 429(프록시 등) — 0이어야 정상
// status 0 = 응답 자체를 못 받음(연결 거부·리셋·타임아웃). 429와 완전히 다른 신호다 — 429는
// "질서 있게 거절"이고 이건 "수용 큐가 넘쳐 그냥 끊김"이다. 둘을 뭉뚱그리면 유입 제어가
// 일하는 중인지 서버가 무너지는 중인지 구분할 수 없다.
const connErrors = new Counter('conn_errors');
const other4xx = new Counter('other_4xx');         // 429 아닌 4xx(409 중복·400 등)

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: VUS },   // 급증
        { duration: '30s', target: VUS },   // 폭주 유지 — 이 구간이 측정 대상
        { duration: '10s', target: 0 },     // 급감
      ],
    },
  },
  thresholds: {
    server_errors: ['rate<0.01'],
    ok_duration: ['p(95)<1500'],
    // 실험 성립 조건 — 전역 층이 한 번도 안 걸렸으면 이 스크립트는 아무것도 측정하지 못했다.
    shed_global: ['count>0'],
  },
};

function record(res) {
  const is5xx = res.status >= 500;
  const isOk = res.status >= 200 && res.status < 300;
  serverErrors.add(is5xx);
  if (isOk) {
    okDuration.add(res.timings.duration);
  } else if (res.status === 0) {
    connErrors.add(1);
  } else if (res.status === 429) {
    // 어느 층이 잘랐는지 귀속한다 — 같은 429라도 운영 대응이 정반대다.
    const scope = res.headers['X-Ratelimit-Scope'];   // k6는 헤더명을 Title-Case로 정규화한다
    if (scope === 'global') shedGlobal.add(1);
    else if (scope === 'user' || scope === 'ip') shedUser.add(1);
    else shedUnknown.add(1);
  } else if (res.status >= 400) {
    other4xx.add(1);
  }
  return { isOk, isShed: res.status === 429 };
}

// setup: 계정을 미리 만들어 토큰 풀을 짓는다. VU마다 다른 계정을 써야 per-user 한도가
// 계정별로 흩어지고, 합계가 전역 상한을 넘어 바깥 층이 깨어난다.
// 주의: 가입/로그인 자체도 IP 기준 제한(5/s)을 타므로 setup에서 간격을 둔다 — 안 그러면
// setup이 자기가 만든 제한에 걸려 토큰을 못 받는다(이 스크립트를 처음 돌릴 때 실제로 겪는다).
export function setup() {
  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `k6-spike-${run}-${i}@load.test`;
    const password = 'k6-load-only-1234';

    const signup = http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'signup' } });
    check(signup, { 'signup 201': (r) => r.status === 201 });

    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
    check(login, { 'login 200': (r) => r.status === 200 });
    if (login.status === 200) tokens.push(login.json('token'));

    sleep(0.5);   // IP 제한(5/s) 회피 — setup은 부하가 아니라 준비 단계다
  }
  if (tokens.length < 21) {
    // 21개 미만이면 계정 × 5/s가 전역 상한 100/s를 넘지 못해 실험이 성립하지 않는다.
    throw new Error(`토큰 ${tokens.length}개 — 전역 층을 깨우려면 21개 이상 필요하다`);
  }
  console.log(`계정 ${tokens.length}개 준비 — 이론상 제공량 ${tokens.length * 5}/s vs 전역 상한 100/s`);
  return { tokens };
}

export default function (data) {
  // VU를 계정에 고르게 분산한다 — 한 계정에 몰리면 per-user 층에서 먼저 다 잘려
  // 다시 spike-test.js와 같은 상황(전역 층 미도달)이 된다.
  const auth = `Bearer ${data.tokens[(__VU - 1) % data.tokens.length]}`;

  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: 1, quantity: 1 }],
  }), {
    headers: { 'Content-Type': 'application/json', Authorization: auth },
    tags: { name: 'order' },
  });

  const order = record(orderRes);
  check(orderRes, { 'order ok or shed': () => order.isOk || order.isShed });
  if (!order.isOk) {
    sleep(0.3);
    return;
  }

  const body = orderRes.json();

  const confirmRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `spike-multi-${uuidv4()}`,
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
  check(confirmRes, { 'confirm ok or shed': () => confirm.isOk || confirm.isShed });

  sleep(0.1);
}
