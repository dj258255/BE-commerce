import http from 'k6/http';
import { check } from 'k6';

/**
 * #334 — 복구 배치가 도는 동안 관계없는 읽기 API 가 느려지는지 본다. 열린 루프, 고정 도착률.
 *
 *   k6 run -e BASE_URL=http://localhost:18081 -e RATE=20 -e DURATION=300s k6/read-steady.js
 */
const BASE = __ENV.BASE_URL || 'http://localhost:18080';
const PATH = __ENV.READ_PATH || '/api/v1/products?size=24';

export const options = {
  scenarios: {
    read: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 20), timeUnit: '1s',
      duration: __ENV.DURATION || '300s',
      preAllocatedVUs: 20, maxVUs: 100,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE}${PATH}`);
  check(res, { '2xx': (r) => r.status >= 200 && r.status < 300 });
}
