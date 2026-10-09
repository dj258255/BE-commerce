# R15: 한정 수량 동시 주문 — 거절 응답 p95 200ms

목표(요구사항): 방송 특가 한정 50개 상품에 1,000명이 동시에 1개씩 주문하면 **확정 50·거절
950**이 되고, **거절 응답의 p95가 200ms 미만**이며, 거절 경로는 **결제 호출을 하지 않는다**
(R12.2 "수량 선점 실패는 결제를 타지 않고 거절"과 같은 경로를 수치로 확인).

이 b-studio 샌드박스에는 k6가 없다. 그래서 측정 자체는 **호스트에서** 돌린다. 이 문서는
그 실행 순서와, 샌드박스 안에서 무엇을 이미 확인해 뒀는지를 적는다.

## 왜 준비를 2단계로 나눴나

k6 `setup()` 안에서 직접 계정 1,000개를 가입·로그인하면 가입 IP 제한(5/s, `RateLimitFilter`)
때문에 준비만으로 몇 분이 걸리고, **그 준비 시간이 "동시 주문 1,000건"이라는 측정 구간에
섞여 버린다.** 그래서 준비를 완전히 분리했다:

1. `tools/prepare-live-order-broadcast.sh` — 방송을 만들고 상품을 한정 수량만큼 고정한다
   (상품 고정은 LIVE 방송에서만 되므로 잠깐 실 RTMP 송출을 붙였다가 고정 후 바로 끈다 —
   주문 생성은 고정 상태만 보고 판정하므로 이후 방송이 계속 LIVE일 필요는 없다, ADR-085).
2. `tools/prepare-live-order-viewers.sh` — 시청자 계정 N명을 가입·로그인해 토큰을 JSON
   파일로 저장해 둔다(기본 `/tmp` — 로그인 토큰 1,000명분을 담은 민감한 산출물이라
   저장소 안에는 남기지 않는다).
3. `k6/live-order-flash-sale.js` — 위 둘이 미리 끝나 있는 상태에서, 저장된 토큰으로 **오직
   동시 주문 1,000건**만 쏜다. 계정 준비 지연이 전혀 섞이지 않는다.

## 실행 순서(호스트에서)

commerce가 뜬 주소를 `BASE_URL`로 준다(b-studio 미리보기 URL 또는 포트 포워딩 주소).

```bash
# 1) 방송 생성 + 상품을 한정 50개로 고정 → BROADCAST_ID를 받는다
BASE_URL=http://<host>:8080 LIMIT=50 ./tools/prepare-live-order-broadcast.sh
# 출력 마지막 줄: BROADCAST_ID=<숫자>

# 2) 시청자 1,000명 계정·토큰 준비(가입 IP 제한 때문에 약 5분 걸린다)
#    토큰 파일은 기본 /tmp에 쓴다(저장소 밖 — 로그인 토큰이라 커밋하지 않는다)
BASE_URL=http://<host>:8080 VIEWER_COUNT=1000 \
  OUT_FILE=/tmp/live-order-viewers-tokens.json \
  ./tools/prepare-live-order-viewers.sh

# 3) k6로 1,000명 동시 주문(이 구간만 측정에 들어간다)
k6 run \
  -e BASE_URL=http://<host>:8080 \
  -e BROADCAST_ID=<1에서 받은 값> \
  -e PRODUCT_ID=1 \
  -e LIMIT=50 \
  -e VUS=1000 \
  -e TOKENS_FILE=/tmp/live-order-viewers-tokens.json \
  k6/live-order-flash-sale.js
```

### k6가 확인하는 것

- `live_order_confirmed` 카운터 `count == LIMIT`(50)
- `live_order_sold_out` 카운터 `count == VUS - LIMIT`(950)
- `live_order_rejected_duration_ms` — **409(매진 거절) 응답에만 샘플을 쌓는 Trend**이고,
  이 Trend의 `p(95) < 200`을 threshold로 건다(R15 NFR "거절 응답만 필터해 p95 집계"와 같은 방식).
- `live_order_payment_calls` — 확정(201) 분기 안에서만 호출·집계된다. 거절(409) 분기는
  코드상 이 호출에 도달하는 경로가 없다 — 그래서 이 카운터 값이 항상 확정 건수와 같다는
  것 자체가 "거절 경로의 결제 호출 수 0"의 증거다. `handleSummary`가 두 숫자를 나란히 찍는다.

thresholds 중 하나라도 어긋나면 k6가 비정상 종료 코드로 끝난다(CI/PR 게이트로 그대로 쓸 수 있다).

## 샌드박스 안에서 확인한 것 (이 저장소, 실제로 실행함)

k6가 없어 1,000명 규모의 본 측정은 못 돌렸지만, **준비 스크립트 둘은 작은 규모로 실제로
돌려 동작을 확인했다**:

- `tools/prepare-live-order-viewers.sh` (`VIEWER_COUNT=6`, `OUT_FILE=/tmp/...`) → 계정 6개
  가입·로그인 성공, 토큰 6개가 JSON 배열로 저장소 밖 파일에 저장됨.
- `tools/prepare-live-order-broadcast.sh` (`LIMIT=5`) → 방송 생성 → 실 ffmpeg RTMP 송출로
  LIVE 전이 → 상품을 한정 5개·₩9,900로 고정(`PINNED` 응답 확인) → `BROADCAST_ID` 출력.

`k6/live-order-flash-sale.js`는 `node --check`로 문법만 확인했다(k6 런타임 전용 모듈
`k6/http`·`k6/metrics`는 샌드박스에 없어 실제 실행은 호스트에서 해야 한다).

## 참고: 1,000명 전원 준비가 느린 이유

가입 IP 제한(5/s) 여유로 계정마다 0.25초씩 띄운다 — 1,000명 기준 약 5분. 같은 IP에서
여러 번 돌리면 쿨다운에 걸릴 수 있으니, 재시도할 땐 몇 분 간격을 두거나 다른 `VIEWER_COUNT`로
시작하세요. 준비된 토큰이 `VUS`보다 적게 끝났다면(가입 실패가 있었던 경우) k6 실행의
`VUS`를 `tools/prepare-live-order-viewers.sh`가 알려주는 실제 준비 수에 맞추면 된다.
