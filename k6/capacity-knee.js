import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 처리 능력의 무릎(knee)을 찾는다 — <b>전역 상한을 정하기 위한 측정</b>.
 *
 * <p>왜 필요한가: {@code app.ratelimit.global-per-sec}의 기본값 100은 <b>측정 없이 정한 숫자</b>다.
 * 다계정 실측(성능 리포트 9절)에서 이 서버의 실제 처리 능력이 ~120/s로 나왔으니, 100은 능력보다
 * 낮게 잡혀 있어 처리할 수 있는 일을 버리고 있었다. 그런데 그 ~120은 <b>맥북 로컬</b> 값이라
 * 운영 기본값을 바꾸는 근거로는 못 쓴다. 배포 환경마다 다시 재야 한다.
 *
 * <p>이 스크립트가 그 재측정이다. 앞의 스파이크 스크립트와 목적이 다르다 —
 * 스파이크는 "제어가 있을 때 무슨 일이 일어나는가"를 보고, 이건 <b>제어를 끄고 서버가
 * 어디서 꺾이는지</b>를 본다. 그래서 반드시 {@code APP_RATELIMIT_ENABLED=false}로 돌린다.
 *
 * <h3>무릎이란</h3>
 * 도착률을 계단식으로 올리면 어느 지점까지는 처리량이 따라 오르다가, 그 뒤로는
 * <b>처리량은 평평한데 지연만 오른다.</b> 그 꺾이는 지점이 무릎이고, 서버가 실제로 소화하는 양이다.
 * 무릎 너머로 들어온 요청은 처리되는 게 아니라 <b>대기열에 쌓일 뿐</b>이다.
 *
 * <p>전역 상한은 이 무릎 근처에 둔다. 훨씬 낮으면 멀쩡한 요청을 버리고, 훨씬 높으면 상한이
 * 아무것도 막지 못해 대기열이 자란다.
 *
 * <h3>실행</h3>
 * <pre>
 * docker compose down -v && docker compose up -d mysql redis   # 매번 초기화 — 안 하면 회차 비교 불가
 * APP_RATELIMIT_ENABLED=false ./gradlew -p commerce bootRun
 * k6 run k6/capacity-knee.js
 * </pre>
 *
 * <h3>판독</h3>
 * 단계별 요약을 콘솔에 찍는다. <b>처리량이 더 이상 안 오르는데 p95만 오르기 시작하는 단계</b>가
 * 무릎이다. 그 단계의 처리량을 {@code APP_RATELIMIT_GLOBAL}로 잡으면 된다(경로당 값이므로,
 * 여기서 나온 총 처리량을 부하가 실제로 닿는 경로 수로 나눠 쓴다).
 *
 * <p>주의: 5xx가 나기 시작하면 이미 무릎을 한참 지난 것이다. 그 지점이 아니라 <b>그 전</b>을 본다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNTS = Number(__ENV.ACCOUNTS || 40);

// 계단: 목표 도착률(req/s). 환경이 빠르면 위쪽을 늘린다.
const STEPS = (__ENV.STEPS || '40,60,80,100,120,140,160,200,240').split(',').map(Number);
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 30);

const stepLatency = {};
const stepOk = {};
const stepConn = {};   // status 0 = 응답을 못 받음(연결 거부·리셋·타임아웃)
const stepErr = {};    // 5xx = 서버가 에러를 냄
STEPS.forEach((rate) => {
  stepLatency[rate] = new Trend(`p95_at_${rate}rps`, true);
  stepOk[rate] = new Rate(`ok_at_${rate}rps`);
  stepConn[rate] = new Counter(`conn_at_${rate}rps`);
  stepErr[rate] = new Counter(`err5xx_at_${rate}rps`);
});

/**
 * 응답 하나를 단계별 지표에 반영한다.
 *
 * <p>연결 실패(status 0)와 5xx를 <b>따로</b> 센다. 둘은 전혀 다른 신호다. 5xx는 서버가 요청을
 * 받아 처리하다 실패한 것이고, status 0은 <b>수용 큐가 넘쳐 요청이 서버에 닿지도 못한</b> 것이다.
 * 무릎 측정에서 실제로 먼저 나타나는 건 후자다 — 서버는 에러를 내지 않고 그냥 안 받는다.
 */
function record(rate, res) {
  const ok = res.status >= 200 && res.status < 300;
  stepOk[rate].add(ok);
  if (ok) stepLatency[rate].add(res.timings.duration);
  else if (res.status === 0) stepConn[rate].add(1);
  else if (res.status >= 500) stepErr[rate].add(1);
  return ok;
}

export const options = {
  scenarios: Object.fromEntries(STEPS.map((rate, i) => [
    `step_${rate}`,
    {
      executor: 'constant-arrival-rate',   // VU가 아니라 <도착률>을 고정한다 — 무릎 측정의 핵심.
      rate,                                 // ramping-vus로는 서버가 느려질수록 도착률이 같이 줄어
      timeUnit: '1s',                       // 병목을 가린다(닫힌 루프의 함정).
      duration: `${STEP_SECONDS}s`,
      startTime: `${i * STEP_SECONDS}s`,
      preAllocatedVUs: 50,
      maxVUs: 1000,                         // 지연이 늘면 VU가 더 필요하다 — 넉넉히 준다
      exec: 'load',
      env: { STEP_RATE: String(rate) },
      tags: { step: String(rate) },
    },
  ])),
  thresholds: {
    // 실패는 5xx만. 429가 나오면 제어를 안 끈 것이므로 아래 setup에서 잡는다.
    'http_req_failed{expected_response:false}': ['rate<0.05'],
  },
};

export function setup() {
  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `k6-knee-${run}-${i}@load.test`;
    const password = 'k6-load-only-1234';
    http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } });
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }),
      { headers: { 'Content-Type': 'application/json' } });
    if (login.status === 200) tokens.push(login.json('token'));
    sleep(0.5);   // 가입·로그인도 IP 제한을 타므로 간격을 둔다
  }
  if (tokens.length === 0) throw new Error('토큰을 하나도 못 받았다 — 앱이 떠 있는지 확인하라');

  // 제어가 켜져 있으면 무릎이 아니라 "설정한 한도"를 재게 된다. 그건 측정이 아니라 동어반복이다.
  const probe = http.post(`${BASE}/api/v1/orders`, JSON.stringify({ items: [{ productId: 1, quantity: 1 }] }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokens[0]}` } });
  if (probe.status === 429) {
    throw new Error('429가 돌아왔다 — APP_RATELIMIT_ENABLED=false로 다시 띄워라');
  }
  console.log(`계정 ${tokens.length}개 준비. 계단: ${STEPS.join(' → ')} req/s (각 ${STEP_SECONDS}초)`);
  return { tokens };
}

export function load(data) {
  const rate = __ENV.STEP_RATE;
  const auth = `Bearer ${data.tokens[__VU % data.tokens.length]}`;

  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    items: [{ productId: 1, quantity: 1 }],
  }), { headers: { 'Content-Type': 'application/json', Authorization: auth }, tags: { name: 'order' } });

  const ok = record(rate, orderRes);
  check(orderRes, { 'order 2xx': () => ok });
  if (!ok) return;

  const body = orderRes.json();
  const confirmRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `knee-${uuidv4()}`, orderNo: body.orderNo, amount: body.totalAmount,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': uuidv4(),
      Authorization: auth,
    },
    tags: { name: 'confirm' },
  });
  record(rate, confirmRes);
}

/**
 * 단계별 결과를 한 표로 찍고 무릎을 판정한다.
 *
 * <p><b>판정 기준은 지연이 아니라 성공률이다.</b> 성공률이 무너지기 시작하면 느린 요청이 실패로
 * 빠져나가 <b>성공한 것들의 p95는 오히려 내려간다</b> — 지연만 보면 "회복됐다"고 잘못 읽는다.
 * (실제로 이 스크립트의 첫 판정 로직이 그 함정에 빠져 무릎을 세 번 찍었다.)
 * 그래서 무릎은 <b>성공률이 온전한 마지막 단계</b>로 정의한다.
 */
export function handleSummary(data) {
  const OK_THRESHOLD = 0.99;
  const rows = [];
  for (const rate of STEPS) {
    const okM = data.metrics[`ok_at_${rate}rps`];
    const p95M = data.metrics[`p95_at_${rate}rps`];
    if (!okM) continue;
    rows.push({
      rate,
      ok: okM.values.rate,
      p95: p95M && p95M.values['p(95)'] ? p95M.values['p(95)'] : 0,
      conn: (data.metrics[`conn_at_${rate}rps`] || { values: { count: 0 } }).values.count,
      err: (data.metrics[`err5xx_at_${rate}rps`] || { values: { count: 0 } }).values.count,
    });
  }

  // 무릎 = 성공률이 온전한 마지막 단계(그 뒤로 한 번이라도 무너지면 거기서 끊는다).
  let knee = null;
  for (const r of rows) {
    if (r.ok >= OK_THRESHOLD) knee = r.rate;
    else break;
  }

  const lines = ['', '  도착률   성공률     p95      연결실패   5xx', '  ' + '-'.repeat(52)];
  for (const r of rows) {
    const mark = r.rate === knee ? '  ← 무릎' : (r.ok < OK_THRESHOLD ? '  (포화)' : '');
    lines.push(`  ${String(r.rate).padStart(5)}/s  ${(r.ok * 100).toFixed(1).padStart(6)}%  `
      + `${r.p95.toFixed(0).padStart(6)}ms  ${String(r.conn).padStart(8)}  ${String(r.err).padStart(5)}${mark}`);
  }
  lines.push('');
  if (knee === null) {
    lines.push('  첫 단계부터 성공률이 무너졌다. STEPS를 더 낮은 값부터 시작하라.');
  } else if (knee === STEPS[STEPS.length - 1]) {
    lines.push(`  마지막 단계(${knee}/s)까지 온전했다 — 무릎을 못 찾았다. STEPS를 더 높여 다시 재라.`);
  } else {
    lines.push(`  이 환경의 처리 능력 ≈ ${knee} req/s (도착률 기준, 유입 제어 OFF).`);
    lines.push(`  APP_RATELIMIT_GLOBAL은 이 값을 부하가 닿는 경로 수로 나눠 잡는다.`);
  }
  lines.push('');
  lines.push('  연결실패(status 0)가 5xx보다 먼저 오르면 수용 큐 포화다 —');
  lines.push('  서버가 에러를 내는 게 아니라 요청을 받지 않는 것이다.');
  lines.push('');
  return { stdout: lines.join('\n') };
}
