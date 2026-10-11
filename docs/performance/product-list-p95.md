# 상품 목록 조회 API — 동시 사용자 100명, p95 300ms 미만

## 요구사항

`GET /api/v1/products`(상품 목록 조회, 인증 불필요 — `CatalogController`)는 동시 사용자 100명이
조회해도 응답 시간 p95가 300ms 미만이어야 한다. 서비스 코드는 바꾸지 않고, 이 요구를 앞으로도
반복해서 확인할 수 있는 수단만 둔다.

## 확인 수단: `k6/product-list-p95.js`

`k6/checkout-load.js`와 같은 패턴 — k6 스크립트 안에 하드 임계치(`thresholds`)를 둬서, 기준을
어기면 k6 자체가 실패 종료 코드로 끝난다. 동시 사용자 수를 그대로 표현하기 위해 닫힌 루프
(`constant-vus`, 기본 VUS=100, 30초)를 쓴다.

- `http_req_duration`: `p(95)<300` (요구사항 그대로)
- `http_req_failed`: `rate<0.01`

### 실행 절차 (샌드박스 안전 — 호스트 전달 포트로 쏘지 않는다)

b-studio 샌드박스에는 k6가 설치돼 있지 않고, 호스트에 전달된 포트로 부하를 쏘면 colima의 ssh
포트 전달 통로(docker 소켓 + 포트 전달 + 공유 폴더 마운트를 동시에 나르는 연결)가 끊길 수 있다
— 자세한 배경은 `docs/performance/live-order-r15.md` 참고. 그래서 항상 commerce와 같은 compose
네트워크 안에서, 서비스 이름으로 돌린다:

```bash
NETWORK="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' \
  "$(docker ps -q -f 'name=-commerce-' | head -1)")"

docker run --rm --network "$NETWORK" \
  -v "$(pwd)/k6:/scripts:ro" \
  -e BASE_URL=http://commerce:8080 \
  -e VUS=100 \
  -e DURATION=30s \
  grafana/k6 run /scripts/product-list-p95.js
```

PR 전/배포 전 등 필요할 때마다 위 명령을 그대로 다시 돌리면 된다 — 통과/실패가 명확한 종료
코드로 나온다.

## 지금 가진 신호 (k6는 아님 — 참고용)

이번 세션의 샌드박스에는 docker-in-docker로 k6 컨테이너를 새로 띄울 수 없어서, 같은 compose
네트워크 안(web 컨테이너 → `http://commerce:8080`, 전달 포트 거치지 않음)에서 Node 내장
`fetch`로 100개 요청을 동시에 보내 임시로 재 봤다:

```
n=100, 실패 0, p50=146ms, p95=189ms, p99=195ms, max=195ms
```

현재 시드 데이터(상품 3건)로는 300ms 기준을 여유 있게 통과한다.

**주의**: 이 수치는 k6가 재는 것과 같은 측정이 아니다(커넥션 재사용, HTTP 처리 방식이 다르다) —
공식 판정은 위 `k6/product-list-p95.js` 실행 결과로 한다. 또한 지금 카탈로그가 상품 3건뿐이라
운영 규모(수만 건 이상)에서는 p95가 달라질 수 있다 — `docs/performance/search-filters-scale.md`
(#244)에서도 카탈로그 규모가 커지면 지연이 함께 올라감을 실측한 바 있다.
