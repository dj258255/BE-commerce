# nginx 뒤 API 두 대를 차례로 재시작할 때 결제가 끊기는가 (#338)

[ADR-029](../adr/ADR-029-deployment-unit-vs-service-boundary.md)는 "API 롤링 자체는 재지 않았다. 로컬에는 로드밸런서가 없다"고 적었다.
nginx 하나를 앞에 두고 재쟀다. 예측과 판정 기준은 [이슈 #338](https://github.com/dj258255/BE-commerce/issues/338)에 **측정 전에** 적었다.

- **두 대를 차례로 재시작만 하면 끊긴다.** 두 번 재서 30건(1초) · 281건(9.3초). 둘 다 두 번째 리플리카를 멈춘 순간 시작했고 nginx 가 `no live upstreams` 로 돌려보냈다
- **리플리카를 LB 에서 빼고 재시작한 뒤 다시 넣으면 끊기지 않는다.** 두 번 재서 0 / 10,406 · 0 / 10,380
- 어느 경우든 **중복 결제 0 건.** 한 대를 LB 없이 재시작한 기준선은 251건 · 8.2초로 ADR-029 실험 3(270건 · 9초)을 재현했다

## 방법

- `k6/redeploy-blast-radius.js`: 체크아웃(주문 → 결제 확인, 멱등키) 30VU · 3분. 60초 지점부터 재시작(ADR-029 실험 3 과 같은 스크립트)
- `tools/run-rolling-deploy.sh single | rolling | drain`
  - **single**: LB 없이 한 대 재시작
  - **rolling**: nginx(라운드 로빈, `proxy_next_upstream error timeout`, upstream keepalive) 뒤 A → B 차례로 재시작. 앱은 graceful shutdown(20초)
  - **drain**: 재시작 전에 그 리플리카를 upstream 에서 `down` 으로 빼고(reload) 2초 뒤 재시작, 뜨면 다시 넣는다(reload)
- 일회용 MySQL · Redis, 실행마다 DB 를 새로 만들었다. 중복 결제는 같은 주문 번호에 결제 행이 둘 이상인 주문 수

## 결과

| 실행 | 실패 / 전체 | 실패 구간 | 실패 종류 | 중복 결제 | 확정된 결제 |
|---|---:|---:|---|---:|---:|
| 대조(재시작 없음, 60초) | 0 / 3,480 | — | — | — | — |
| single | 251 / 10,189 | 8.2초 | 주문 연결 거부(상태 0) 251 | 0 | 5,083 |
| rolling | 30 / 10,388 | 1.0초 | 주문 502 30 | 0 | 5,289 |
| rolling 2회차 | 281 / 10,137 | 9.3초 | 주문 502 281(`no live upstreams` 280) | 0 | 5,038 |
| **drain** | **0 / 10,406** | — | — | 0 | 5,313 |
| **drain 2회차** | **0 / 10,380** | — | — | 0 | 5,300 |

rolling 2회차의 단계별 시각(첫 실패를 0 으로):

| 시각 | 사건 |
|---:|---|
| −9.87초 | A 에 SIGTERM, 0.1초 뒤 종료 |
| −0.03초 | A 기동 완료 |
| −0.02초 | B 에 SIGTERM, 0.16초 뒤 종료 |
| 0 ~ +9.26초 | 주문 502 281건 |
| +9.34초 | B 기동 완료 |

## 왜 끊겼나

- A 가 내려가 있는 동안 nginx 는 A 로 보낸 연결이 실패할 때마다 A 를 `fail_timeout`(기본 10초) 동안 죽은 것으로 표시한다. 그동안 A 를 가끔 다시 찔러 보고, 실패하면 표시를 갱신한다
- A 가 떠도 그 표시가 풀리기 전에는 트래픽을 받지 못한다. 그 사이 B 를 멈추면 살아 있는 upstream 이 없어 `no live upstreams` 502 가 난다
- 표시가 언제 갱신됐는지에 따라 이 구간이 0~10초 사이에서 갈린다. 두 회차가 1초와 9.3초로 갈린 이유다
- drain 은 reload 로 upstream 을 다시 읽어 이 표시를 지우고, 빠진 리플리카에는 트래픽을 보내지 않는다

예측(이슈)은 "연결을 맺지 못한 요청은 POST 라도 nginx 가 다른 리플리카로 넘기므로 0 이거나 몇 건"이었다. 넘기는 것은 맞았지만 **넘길 곳이 없어지는 구간**을 보지 못했다.
graceful shutdown 은 진행 중인 요청을 끝냈다(종료까지 0.1~0.16초). 종료 도중 끊긴 것은 재사용 연결에 실린 1건뿐이고(아래), 나머지 280건은 LB 가 보낼 곳이 없다고 판단한 것이다.

## 측정을 두 번 버렸다

- 1차: nginx 가 `host.docker.internal` 을 IPv4 · IPv6 둘로 풀어 리플리카마다 upstream 이 둘이 됐다. IPv6 는 컨테이너에서 닿지 않아 워밍업부터 502 가 났다. upstream 을 IPv4 하나로 고정했다
- 2차: nginx 가 요청마다 새 연결을 맺는데 Docker Desktop 의 호스트 중계가 초당 약 55건에서 연결을 1초 안에 못 맺었다(`upstream timed out ... while connecting`). upstream keepalive 를 켜고 연결 제한 시간을 3초로 올린 뒤, **재시작 없는 대조가 0 / 3,480** 인 것을 확인하고 다시 쟀다
- 두 번 모두 재시작 전부터 실패가 퍼져 있어 해석 전에 버렸고, 이유는 이슈 댓글에 남겼다

## 이 표가 말하지 않는 것

- keepalive 의 대가(종료 중인 리플리카가 닫은 재사용 연결에 보낸 POST 는 nginx 가 다시 보내지 않는다)는 2회차에서 1건(`upstream prematurely closed connection`) 보였다. 드물지만 0 은 아니다
- K8s 처럼 준비 상태(readiness)로 트래픽을 끊고 넣는 환경에서는 drain 이 기본 동작이다. 이 실험은 그 동작이 없을 때 무엇이 깨지는지를 보인다
- 로컬 단일 장비 · 앱 두 대 · 같은 DB. 앱 기동이 약 9초라 실패 구간의 상한도 이 맥의 값이다

## 재현

```bash
./gradlew -p commerce bootJar
bash tools/run-rolling-deploy.sh single
bash tools/run-rolling-deploy.sh rolling
bash tools/run-rolling-deploy.sh drain
```

원자료: [`raw/20260926-a029-338/`](raw/20260926-a029-338/) (실행마다 단계별 시각 · 결과 요약 · k6 출력 · nginx 설정과 로그 · 같은 맥의 CPU, 일회용 DB 로 돌린 러너 `run.sh`)
