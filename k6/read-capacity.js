import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

/**
 * <b>조회 경로</b>의 처리 능력을 잰다 — 이 저장소에서 처음이다.
 *
 * <p>왜 필요한가: k6 스크립트 아홉 개가 전부 쓰기 경로였다. 체크아웃·스파이크·정산 경합·재배포까지
 * 다 재면서 <b>조회는 한 번도 안 쟀다.</b> 인덱스를 걸어 한 건의 비용은 65.6ms → 0.6ms로 줄였지만
 * (`docs/24-조회-인덱스-실측.md`), 그건 커넥션 하나로 잰 단건 비용이다.
 * <b>동시에 들어올 때도 그런지는 다른 질문이다.</b>
 *
 * <p>이 측정으로 답하려는 것은 둘이다.
 * <ol>
 *   <li>조회 경로가 어디서 꺾이는가 — 쓰기 경로의 무릎과 비교할 수 있는 숫자가 생긴다
 *   <li><b>읽기 캐시가 필요한가</b> — 지금은 캐시가 없고, 없는 이유를 "근거가 없어서"라고 적어 뒀다.
 *       근거가 없던 건 <b>재 본 적이 없어서</b>다. 재면 필요하다는 근거나 필요 없다는 근거가 생긴다
 * </ol>
 *
 * <p>도착률을 고정한다(`constant-arrival-rate`). 이 저장소는 한 번 닫힌 루프로 재서 용량을
 * 과소평가한 적이 있다 — 서버가 느려지면 VU가 다음 요청을 늦게 보내 <b>도착률이 같이 줄어</b>
 * 병목이 가려진다. 조회는 응답이 빨라 그 함정에 더 잘 빠진다.
 *
 * <h3>실행</h3>
 * <pre>
 * ./gradlew -p commerce bench -Pprofile=read      # 시드부터 리포트까지 한 번에
 * </pre>
 *
 * <p>제어를 끄고 돌린다. 켜 두면 무릎이 아니라 <b>설정한 한도</b>를 재게 된다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNTS = Number(__ENV.ACCOUNTS || 40);
const STEPS = (__ENV.STEPS || '100,200,400,700,1000,1400,1800,2400').split(',').map(Number);
// 20초로 재면 단계마다 p95 가 4~6배씩 튀어 무릎을 못 정한다(1400/s 58ms 인데 1600/s 20ms 가
// 나왔다 — 낮은 부하가 더 느리다는 건 노이즈다). 60초로 늘리니 5→6→7→10→27ms 로 단조로워졌다.
// 부하기와 서버가 한 기계에 있어 GC·JIT 가 단계 경계에 걸리는 탓이라, 짧게 재면 안 된다.
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 60);

const stepLatency = {}, stepOk = {}, stepConn = {}, stepErr = {}, stepReqs = {};
STEPS.forEach((rate) => {
  stepLatency[rate] = new Trend(`p95_at_${rate}rps`, true);
  stepOk[rate] = new Rate(`ok_at_${rate}rps`);
  stepConn[rate] = new Counter(`conn_at_${rate}rps`);
  stepErr[rate] = new Counter(`err5xx_at_${rate}rps`);
  stepReqs[rate] = new Counter(`reqs_at_${rate}rps`);   // 실제로 보낸 수 — 목표 도착률과 대조한다
});

function record(rate, res) {
  stepReqs[rate].add(1);
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
      executor: 'constant-arrival-rate',
      rate, timeUnit: '1s',
      duration: `${STEP_SECONDS}s`,
      startTime: `${i * STEP_SECONDS}s`,
      preAllocatedVUs: 100, maxVUs: 2000,
      exec: 'load',
      env: { STEP_RATE: String(rate) },
      tags: { step: String(rate) },
    },
  ])),
  thresholds: { 'http_req_failed{expected_response:false}': ['rate<0.05'] },
  setupTimeout: '10m',   // 계정이 많으면 로그인(비밀번호 해싱)만으로 기본 60초를 넘는다(#352)
};

export function setup() {
  // 시드(bench.sh seed_read_load)가 주문을 넣은 계정(k6-read-{i})으로 로그인한다. 예전에는 여기서 새 계정(k6-read-{시각}-{i})을
  // 만들어 **주문이 0건인 사용자의 빈 목록**을 쟀다(#352). 그래서 첫 조회가 비어 있으면 멈춘다
  const tokens = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const login = http.post(`${BASE}/api/v1/auth/login`,
      JSON.stringify({ username: `k6-read-${i}@load.test`, password: 'k6-load-only-1234' }),
      { headers: { 'Content-Type': 'application/json' } });
    if (login.status === 200) tokens.push(login.json('token'));
  }
  if (tokens.length === 0) throw new Error('시드 계정으로 로그인하지 못했다 — bench.sh 의 seed_read_load 를 먼저 돌려라');

  const probe = http.get(`${BASE}/api/v1/orders`, { headers: { Authorization: `Bearer ${tokens[0]}` } });
  if (probe.status === 429) throw new Error('429가 돌아왔다 — 유입 제어를 끄고 다시 띄워라');
  if (probe.status !== 200) throw new Error(`조회가 ${probe.status} 를 냈다 — 앱 상태를 확인하라`);
  const rows = probe.json();
  if (!Array.isArray(rows) || rows.length === 0) throw new Error('시드 계정의 주문 목록이 비었다 — 빈 목록을 재게 된다(#352)');

  console.log(`계정 ${tokens.length}개. 계단: ${STEPS.join(' → ')} req/s (각 ${STEP_SECONDS}초)`);
  return { tokens };
}

/**
 * 계정을 요청마다 무작위로 고른다. 한 계정만 두들기면 <b>버퍼 풀에 그 사용자 페이지만 올라가</b>
 * 실제보다 좋게 나온다. 캐시가 필요한지 판단하려는 측정에서 그건 답을 미리 정해 놓는 셈이다.
 * 예전에는 VU 번호로 골라 VU 수만큼의 계정만 쓰였다(#352).
 */
export function load(data) {
  const rate = __ENV.STEP_RATE;
  // 요청마다 계정을 무작위로 고른다. VU 번호로 고르면 VU 수(약 100)만큼의 계정만 쓰여 작업 집합이 작아진다(#352)
  const auth = `Bearer ${data.tokens[Math.floor(Math.random() * data.tokens.length)]}`;
  const res = http.get(`${BASE}/api/v1/orders`, {
    headers: { Authorization: auth }, tags: { name: 'myOrders' },
  });
  const ok = record(rate, res);
  check(res, { 'myOrders 200': () => ok });
}

export function handleSummary(data) {
  const OK = 0.99;
  const rows = [];
  for (const rate of STEPS) {
    const okM = data.metrics[`ok_at_${rate}rps`];
    if (!okM) continue;
    const p95M = data.metrics[`p95_at_${rate}rps`];
    rows.push({
      rate, ok: okM.values.rate,
      p95: p95M && p95M.values['p(95)'] ? p95M.values['p(95)'] : 0,
      // 부하기가 목표 도착률을 실제로 냈는지. maxVUs 에 걸리면 k6 가 요청을 못 보내는데,
      // 그때 오르는 지연은 서버가 아니라 <부하기가 굶은> 것이다. 이걸 구분 안 하면
      // 부하기의 한계를 서버의 한계로 잘못 적게 된다.
      actual: (data.metrics[`reqs_at_${rate}rps`] || { values: { count: 0 } }).values.count / STEP_SECONDS,
      conn: (data.metrics[`conn_at_${rate}rps`] || { values: { count: 0 } }).values.count,
      err: (data.metrics[`err5xx_at_${rate}rps`] || { values: { count: 0 } }).values.count,
    });
  }
  // 쓰기 경로에서 쓰던 "성공률이 온전한 마지막 단계" 기준은 조회에서 안 통한다.
  // 쓰기는 포화가 실패로 나타났다(연결 거부·5xx). 조회는 <아무것도 안 실패하고 지연만 오른다> —
  // 요청이 그냥 줄을 선다. 그래서 성공률로 보면 끝까지 100%고 무릎이 안 잡힌다.
  //
  // 조회의 무릎은 <지연이 평평하다가 꺾이는 지점>이다. 평탄 구간의 p95 를 기준선으로 잡고,
  // 그 몇 배를 넘어서는 첫 단계 <직전>을 무릎으로 본다. 배수는 3배로 뒀다 —
  // 2배는 정상 변동에도 걸리고, 5배는 이미 한참 지난 뒤다.
  const KNEE_FACTOR = 3;
  const DELIVERED = 0.95;      // 목표의 95% 미만이면 그 단계는 부하기 한계라 서버 판정에 못 쓴다
  let knee = null, baseline = null;
  for (const r of rows) {
    if (r.ok < OK) break;                                  // 실패가 나면 거기서 끊는다
    if (r.actual < r.rate * DELIVERED) break;              // 부하기가 못 따라온 단계부터는 무의미
    if (baseline === null || r.p95 < baseline) baseline = r.p95;   // 평탄 구간의 최저값
    if (baseline !== null && r.p95 > baseline * KNEE_FACTOR) break;
    knee = r.rate;
  }

  const lines = ['', '  조회 경로 (GET /api/v1/orders)', '',
    '  목표      실제      성공률     p95      연결실패   5xx', '  ' + '-'.repeat(62)];
  let capped = false;
  for (const r of rows) {
    const short = r.actual < r.rate * DELIVERED;
    if (short) capped = true;
    const mark = short ? '  (부하기 한계 — 서버 수치 아님)'
               : r.rate === knee ? '  ← 무릎'
               : (r.ok < OK ? '  (포화)' : '');
    lines.push(`  ${String(r.rate).padStart(5)}/s  ${r.actual.toFixed(0).padStart(5)}/s  `
      + `${(r.ok * 100).toFixed(1).padStart(6)}%  ${r.p95.toFixed(0).padStart(6)}ms  `
      + `${String(r.conn).padStart(8)}  ${String(r.err).padStart(5)}${mark}`);
  }
  if (capped) {
    lines.push('');
    lines.push('  일부 단계에서 k6 가 목표 도착률을 못 냈다(maxVUs 도달). 그 줄의 지연은');
    lines.push('  서버가 아니라 부하기가 굶은 값이라 서버 판정에 쓰지 않는다.');
    lines.push('  부하기와 서버가 같은 기계에 있으면 위쪽 단계는 원래 못 믿는다.');
  }
  lines.push('');
  if (knee === null) lines.push('  첫 단계부터 무너졌다 — STEPS를 더 낮게 시작하라.');
  else if (knee === STEPS[STEPS.length - 1]) lines.push(`  마지막(${knee}/s)까지 평탄했다 — 더 높여 다시 재라.`);
  else lines.push(`  조회 경로의 처리 능력 ≈ ${knee} req/s (평탄 p95 ${baseline.toFixed(0)}ms, 유입 제어 OFF).`);
  lines.push('');
  lines.push('  판정을 성공률이 아니라 지연으로 한다. 쓰기 경로는 포화가 실패로 나타나지만');
  lines.push('  조회는 아무것도 실패하지 않고 지연만 오른다 — 요청이 줄을 설 뿐이다.');
  lines.push('');
  lines.push('  캐시 판단: 평탄 구간의 p95 가 이미 크면 조회 한 건이 비싼 것이라 캐시가 값을 한다.');
  lines.push('  평탄 p95 가 작고 무릎이 높으면 DB 는 병목이 아니다 — 그때 캐시를 넣는 것은');
  lines.push('  무효화라는 새 문제만 들이는 일이다.');
  lines.push('');
  return { stdout: lines.join('\n') };
}
