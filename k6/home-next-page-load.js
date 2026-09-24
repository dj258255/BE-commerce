// 홈 2쪽 부하(#271). 추천 행 부하(inference-overload.js)와 같이 돌려, 게이트를 거치지 않는 GenPage `/page` 호출이
// 모델 서버를 나눠 쓸 때 무엇이 늦어지는지 본다.
//
// 흐름: setup 에서 계정마다 활동을 심고 1쪽을 받아 2쪽 커서를 만든다. 반복마다 그 커서로 2쪽을 받는다.
// 2쪽의 모든 행이 GENPAGE 면 모델이 만든 쪽이고, 아니면 모델이 실패해 규칙 행으로 물러선 것이다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNTS = Number(__ENV.ACCOUNTS || 8);
const RATE = Number(__ENV.RATE || 10);
const DURATION = __ENV.DURATION || '40s';
const WARMUP_MS = Number(__ENV.WARMUP_MS || 10000);
const MAX_VUS = Number(__ENV.MAX_VUS || 100);
const ITEMS = JSON.parse(open(__ENV.ACTIVITY_ITEMS));

const generated = new Rate('page_generated');
const pageMs = new Trend('page_ms', true);

export const options = {
  setupTimeout: '240s',
  scenarios: {
    pages: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
             preAllocatedVUs: MAX_VUS, maxVUs: MAX_VUS, gracefulStop: '10s' },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const run = Date.now();
  const users = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `k6-page-${run}-${i}@load.test`;
    const password = 'k6-load-only-1234';
    const json = { headers: { 'Content-Type': 'application/json' } };
    http.post(`${BASE}/api/v1/members/signup`, JSON.stringify({ email, password }), json);
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: email, password }), json);
    if (login.status !== 200) {
      continue;
    }
    const token = login.json('token');
    const auth = { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } };
    for (let seq = 1; seq <= 10; seq++) {
      http.post(`${BASE}/api/v1/personalization/activity`,
        JSON.stringify({ itemId: ITEMS[(i * 10 + seq + 500) % ITEMS.length], type: 'VIEW', seq }), auth);
    }
    const first = http.get(`${BASE}/api/v1/personalization/homepage`, auth);
    if (first.status === 200 && first.json('nextCursor')) {
      users.push({ token, cursor: first.json('nextCursor') });
    }
    sleep(0.5);
  }
  if (users.length === 0) {
    throw new Error('2쪽 커서를 하나도 못 만들었다');
  }
  return { users, startedAt: Date.now() };
}

export default function (data) {
  const u = data.users[(__VU - 1) % data.users.length];
  const r = http.get(`${BASE}/api/v1/personalization/homepage?cursor=${u.cursor}`,
    { headers: { Authorization: `Bearer ${u.token}` }, tags: { name: 'page2' } });
  if (Date.now() - data.startedAt < WARMUP_MS) {
    return;
  }
  check(r, { 'page 200': (x) => x.status === 200 });
  if (r.status !== 200) {
    generated.add(false);
    return;
  }
  const rows = r.json('rows') || [];
  generated.add(rows.length > 0 && rows.every((row) => row.strategy === 'GENPAGE'));
  pageMs.add(r.timings.duration);
}

export function handleSummary(data) {
  const m = (name) => (data.metrics[name] || { values: {} }).values;
  const line = [
    `rate=${RATE}`,
    `achieved=${(m('http_reqs').rate || 0).toFixed(0)}`,
    `generated=${((m('page_generated').rate || 0) * 100).toFixed(1)}%`,
    `page_p95=${(m('page_ms')['p(95)'] || 0).toFixed(1)}ms`,
    `page_p99=${(m('page_ms')['p(99)'] || 0).toFixed(1)}ms`,
    `dropped=${(m('dropped_iterations').count || 0)}`,
  ].join('  ');
  return { stdout: '\n[PAGE] ' + line + '\n' };
}
