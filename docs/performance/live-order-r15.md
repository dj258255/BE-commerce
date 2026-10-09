# R15: 한정 수량 동시 주문 — 거절 응답 p95 200ms

목표(요구사항): 방송 특가 한정 50개 상품에 1,000명이 동시에 1개씩 주문하면 **확정 50·거절
950**이 되고, **거절 응답의 p95가 200ms 미만**이며, 거절 경로는 **결제 호출을 하지 않는다**
(R12.2 "수량 선점 실패는 결제를 타지 않고 거절"과 같은 경로를 수치로 확인).

이 b-studio 샌드박스에는 k6가 없다. 그래서 측정은 **grafana/k6 컨테이너를 compose
네트워크에 붙여서** 돌린다(아래 "k6를 호스트에서 바로 돌리면 안 되는 이유" 참고) — host에
k6를 설치해 샌드박스까지 전달된 포트로 부하를 보내지 않는다. 이 문서는 그 실행 순서와,
샌드박스 안에서 무엇을 이미 확인해 뒀는지를 적는다.

## k6를 호스트에서 전달 포트로 바로 돌리면 안 되는 이유

실제로 호스트에서 k6(동시 1,000)를 b-studio의 전달 포트(colima가 ssh로 뚫어 둔 포트)에
대고 쏴 봤다. 결과: **colima의 ssh 포트 전달 통로 자체가 끊겼다** — 그 통로가 도커
소켓·전달 포트·공유 폴더 마운트를 전부 함께 실어 나르기 때문에, 통로가 끊기자 이 셋이
한꺼번에 끊겼다(세션 전체가 멈춘다). 포트 전달은 원래 미리보기 화면을 한두 개 여는
정도의 가벼운 트래픽을 가정한 경로라, 1,000 동시 연결 같은 부하를 실어 보내는 용도가
아니다.

**그래서 k6(부하 생성 구간)는 호스트에서 전달 포트로 돌리지 않는다.** 대신 아래
실행 순서의 3단계처럼 k6 자체를 컨테이너로 띄워 commerce와 같은 compose 네트워크에 붙이고,
포트 전달을 거치지 않고 서비스 이름(`http://commerce:8080`)으로 직접 부른다 — 부하가
컨테이너 사이 네트워크 안에서만 돌아서 호스트↔샌드박스 통로에 부담을 주지 않는다.

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

# 3) k6로 1,000명 동시 주문(이 구간만 측정에 들어간다) — 호스트에 k6를 설치해 전달 포트로
#    쏘지 않는다(위 경고 참고). grafana/k6 컨테이너를 commerce와 같은 compose 네트워크에
#    붙여 서비스 이름으로 직접 부른다.

# 3-1) commerce 컨테이너가 붙어 있는 네트워크 이름을 알아낸다(b-studio가 세션마다 다른
#      compose 프로젝트 이름을 쓰므로 미리 적어 둘 수 없다)
NETWORK="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' \
  "$(docker ps -q -f 'name=-commerce-' | head -1)")"

# 3-2) k6 컨테이너를 그 네트워크에 붙여 돌린다 — BASE_URL은 전달 포트가 아니라 서비스 이름이다.
#      TOKENS_FILE은 2)에서 호스트에 쓴 파일이므로 같은 경로를 컨테이너에 그대로 마운트한다.
docker run --rm --network "$NETWORK" \
  -v "$(pwd)/k6:/scripts:ro" \
  -v /tmp:/tmp:ro \
  -e BASE_URL=http://commerce:8080 \
  -e BROADCAST_ID=<1에서 받은 값> \
  -e PRODUCT_ID=1 \
  -e LIMIT=50 \
  -e VUS=1000 \
  -e TOKENS_FILE=/tmp/live-order-viewers-tokens.json \
  grafana/k6 run /scripts/live-order-flash-sale.js
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

## 측정 결과: R15는 아직 측정되지 않았다

호스트에서 전달 포트로 k6(동시 1,000)를 돌렸을 때 **거절 응답 p95 1,148.5ms**가 나왔다.
이 값은 **무효인 측정**이다 — 아래 "k6를 호스트에서 바로 돌리면 안 되는 이유"에서 설명한
colima ssh 포트 전달 통로를 거쳐 잰 값이라, 측정된 지연 대부분이 commerce의 거절 응답
속도가 아니라 그 통로(port-forward 구간) 자체의 지연·불안정성을 담고 있다. R15의
판정 기준(거절 응답 p95 200ms 미만)은 **commerce가 거절을 얼마나 빨리 돌리는지**를
보는 것이므로, 측정 경로에 통로 지연이 섞인 이 숫자로는 통과·실패 어느 쪽도 판정할 수
없다.

**그래서 R15는 아직 측정되지 않은 상태다.** 이 1,148.5ms를 R15 통과·실패 판정에 쓰지
않는다 — 위 "실행 순서"대로 k6를 commerce와 같은 compose 네트워크의 컨테이너에서
(전달 포트를 거치지 않고) 다시 돌려야 유효한 판정을 할 수 있다. 이번 문서 수정에서는
그 재측정을 하지 않았다(절차·스크립트만 고쳤다).

## 샌드박스 안에서 확인한 것 (이 저장소, 실제로 실행함)

k6가 없어 1,000명 규모의 본 측정은 못 돌렸지만, **준비 스크립트 둘은 작은 규모로 실제로
돌려 동작을 확인했다**:

- `tools/prepare-live-order-viewers.sh` (`VIEWER_COUNT=6`, `OUT_FILE=/tmp/...`) → 계정 6개
  가입·로그인 성공, 토큰 6개가 JSON 배열로 저장소 밖 파일에 저장됨.
- `tools/prepare-live-order-broadcast.sh` (`LIMIT=5`) → 방송 생성 → 실 ffmpeg RTMP 송출로
  LIVE 전이 → 상품을 한정 5개·₩9,900로 고정(`PINNED` 응답 확인) → `BROADCAST_ID` 출력.

`k6/live-order-flash-sale.js`는 `node --check`로 문법만 확인했다(k6 런타임 전용 모듈
`k6/http`·`k6/metrics`는 샌드박스에 없어 실제 실행은 위 "실행 순서"대로 grafana/k6
컨테이너로 해야 한다).

## 참고: 1,000명 전원 준비가 느린 이유

가입 IP 제한(5/s) 여유로 계정마다 0.25초씩 띄운다 — 1,000명 기준 약 5분. 같은 IP에서
여러 번 돌리면 쿨다운에 걸릴 수 있으니, 재시도할 땐 몇 분 간격을 두거나 다른 `VIEWER_COUNT`로
시작하세요. 준비된 토큰이 `VUS`보다 적게 끝났다면(가입 실패가 있었던 경우) k6 실행의
`VUS`를 `tools/prepare-live-order-viewers.sh`가 알려주는 실제 준비 수에 맞추면 된다.
