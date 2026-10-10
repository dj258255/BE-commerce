import http from 'k6/http';
import { check } from 'k6';

/**
 * 상품 목록 조회(GET /api/v1/products) 응답 시간 SLA — 동시 사용자 100명, p95 300ms 미만.
 * 인증이 필요 없는 공개 읽기 표면이다(CatalogController — SecurityConfig가 명시적으로 개방).
 *
 * <p><b>닫힌 루프(constant-vus)를 쓴다</b> — 이 SLA가 묻는 건 "동시 접속자 수가 고정값(100)일 때
 * 지연이 얼마인가"라 VU 수 자체를 조건으로 고정하는 게 맞다(`docs/performance/search-filters-scale.md`
 * #172가 "동시성 1·10·50"을 고정해 같은 방식을 썼다). 용량이 꺾이는 지점을 찾는 질문이었다면
 * 열린 루프(`k6/read-capacity.js`처럼 도착률 고정)가 맞다 — 닫힌 루프는 서버가 느려지면 부하도
 * 같이 줄어 용량을 과소평가할 수 있기 때문이다.
 *
 * <p>이 b-studio 샌드박스에는 k6가 없고, 호스트에서 전달 포트로 k6를 돌리면 colima의 ssh 포트
 * 전달 통로가 끊길 수 있다(`docs/performance/live-order-r15.md` "k6를 호스트에서 바로 돌리면
 * 안 되는 이유" 참고) — 그래서 이 스크립트도 같은 방식으로 돌린다: grafana/k6 컨테이너를
 * commerce와 같은 compose 네트워크에 붙여 서비스 이름(http://commerce:8080)으로 직접 부른다.
 * 실행 절차는 `docs/performance/product-list-p95.md`.
 */
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || '30s';
const LIST_PATH = __ENV.LIST_PATH || '/api/v1/products?page=0&size=20';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    list: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  // 하나라도 어긋나면 k6가 비정상 종료 코드로 끝난다 — CI/PR 게이트로 그대로 쓸 수 있다.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300'],
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE}${LIST_PATH}`, { tags: { name: 'product-list' } });
  check(res, { '200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const d = data.metrics.http_req_duration?.values ?? {};
  const failedRate = data.metrics.http_req_failed?.values?.rate ?? 0;
  const lines = [
    `=== 상품 목록 조회 SLA (VUS=${VUS}, ${DURATION}) ===`,
    `p50=${(d.med ?? 0).toFixed(1)}ms p95=${(d['p(95)'] ?? 0).toFixed(1)}ms `
      + `p99=${(d['p(99)'] ?? 0).toFixed(1)}ms max=${(d.max ?? 0).toFixed(1)}ms`,
    `실패율=${(failedRate * 100).toFixed(2)}% (기준: p95<300ms, 실패율<1%)`,
  ];
  console.log(lines.join('\n'));
  return { stdout: lines.join('\n') + '\n' };
}
