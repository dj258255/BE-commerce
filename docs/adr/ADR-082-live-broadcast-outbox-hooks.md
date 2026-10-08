# ADR-082. 라이브 방송 1단계: MediaMTX 출발점에서 Kafka만 빼고 나머지는 그대로 쓴다

- 상태: **채택**
- 날짜: 2026-10-08
- 관련: R1·R2·R3, [42-라이브커머스-숏폼-명세.md](../42-라이브커머스-숏폼-명세.md) §5·§7(순서 2),
  [ADR-002](ADR-002-outbox-event-publication-registry.md)(Outbox = Event Publication Registry),
  [ADR-029](ADR-029-deployment-unit-vs-service-boundary.md)(배포 단위 분리 기준선),
  [ADR-080](ADR-080-media-gradle-submodule.md)(media Gradle 하위 프로젝트),
  [ADR-081](ADR-081-shorts-transcode-outbox-worker-profile.md)(숏폼의 같은 종류 결정 — 이
  ADR이 그대로 따라간 전례)

## 맥락

명세 5절은 라이브 방송의 출발점을 이렇게 정했다:

| 영역 | 출발점 | 이유 |
|---|---|---|
| 미디어 서버 | MediaMTX 컨테이너 하나(RTMP 송출, LL-HLS 시청, 녹화) | 한 컨테이너로 방송·시청·녹화 |
| 송출 인증·이벤트 | MediaMTX의 HTTP 인증 훅 → 스트림 키 검증, 시작·종료 훅 → **Kafka** | 키 검증과 상태 전이를 기존 백엔드가 맡는다 |

이 1단계(R1 방송 생성·R2 송출 인증·R3 상태 전이)에서 **HTTP 인증 훅은 그대로 쓴다** — 벗어난
것은 하나뿐이다: **시작·종료 훅의 전달을 Kafka가 아니라 Outbox로 받는다.** ADR-081이 숏폼
변환에서 이미 내린 결정과 똑같은 이유이고, 그 ADR을 그대로 따라간다.

## 결정: 시작·종료 훅도 Kafka 대신 Outbox(Event Publication Registry)

**왜 벗어났나**: MediaMTX의 `runOnPublish`/`runOnUnpublish`는 **외부 프로세스(MediaMTX)가
우리 HTTP 엔드포인트를 부르는 것**이지, 우리 애플리케이션이 Kafka에 직접 쓰는 것이 아니다.
그 HTTP 호출을 받은 뒤 "방송 상태를 바꾸고 `live.started`/`live.ended`를 남긴다"는 작업은
**같은 데이터베이스, 같은 jar 안의 일**이다 — 숏폼의 "업로드 완료 → 변환 시작"과 똑같은
모양이다. Kafka를 "훅 받기"와 "이벤트 발행" 사이에 끼우면, HTTP 핸들러 안에서 Kafka
프로듀스가 실패했을 때 "MediaMTX에는 훅 응답을 이미 200으로 보냈는데 우리 쪽 상태는 안
바뀜"이라는 유실 창구가 새로 생긴다 — Outbox(같은 트랜잭션에 커밋)는 이 유실을 원천적으로
막는다(ADR-002).

Spring Modulith의 `@ApplicationModuleListener`를 당장 쓰지는 않는다 — 이 1단계에는 이벤트를
**구독하는 쪽이 아직 없다**(시청 화면 전환 R7, 운영 로그 R31이 다음 단계). 지금은
`ApplicationEventPublisher.publishEvent`로 발행만 하고 Outbox(`event_publication`)에
적재되는 것까지만 확인한다 — 리스너는 구독자가 생기는 다음 단계에서 붙인다. 이것도
ADR-081의 선례와 같다(발행 메커니즘을 먼저 놓고, 소비자는 필요해질 때 붙인다는 건 아니고
— 숏폼은 소비자가 이미 있었지만 여기는 아직 없다는 점이 다르다. 발행 쪽 설계는 같다).

**대가**: 방송 이벤트가 commerce 애플리케이션과 완전히 분리된 별도 소비자(다른 언어·다른
배포 주기의 알림 서비스 등)가 될 수 없다. 지금은 같은 jar 안에서만(그리고 아직 아무도) 소비
한다.

## HTTP 인증 훅은 명세 그대로 — MediaMTX `authHTTPAddress`

벗어나지 않은 부분도 분명히 적는다: R2(스트림 키 검증)는 명세가 이미 "HTTP 인증 훅"이라고
정확히 짚었고, 그대로 구현했다 — `LiveMediaHooksController#auth`가 MediaMTX의
`authHTTPAddress`가 호출하는 자리다. 벗어날 이유가 없었다(이미 HTTP라서 Outbox로 바꿀
대상도 아니다).

## 재접속 유예(R3)를 스케줄러로 스캔한다 — 같은 jar, `local`·`worker` 프로파일

"송출이 30초 안에 다시 붙으면 같은 방송으로 이어야 한다"는 요구를 **상태를 바꾸지 않고
끊긴 시각만 기록**하는 방식으로 구현했다(`LiveBroadcast.recordDisconnect` — 상태는 LIVE
그대로). 유예가 끝났는지는 `LiveBroadcastGraceScheduler`가 주기(기본 5초)로 스캔해 판단한다
— 숏폼의 `ShortsTranscodeListener`·에스크로의 `EscrowAutoReleaseScheduler`와 같은
`@ConditionalOnProperty` 게이트(`app.live.grace-scheduler.enabled`)를 쓴다. API 배포는
기본 꺼짐이고, `worker`·`local` 프로파일에서 켠다(ADR-029의 배포 단위 분리 기준선, R23과
같은 선택).

**왜 "폴링 스캐너"인가 — 이벤트 기반 타이머가 아니라**: 끊김 하나마다 "30초 뒤 깨워 줘"를
스케줄링하는 방식(예: `ScheduledExecutorService`에 건당 지연 작업 등록)도 가능했지만,
그러면 애플리케이션이 재시작될 때 그 타이머가 전부 사라진다 — 재시작 중에 끊긴 방송은
영원히 ENDED가 안 된다. DB에 쌓인 `disconnectedAt`을 주기적으로 다시 읅는 폴링은 재시작과
무관하게 항상 같은 결과를 낸다(멱등) — 정산·에스크로 자동 릴리스가 이미 쓰는 패턴과
같은 이유다.

## 인프라 — MediaMTX를 샌드박스에 띄우지 못했다

`compose.yaml`(저장소 루트, 공식 이미지 `bluenviron/mediamtx`)과 `media/mediamtx.yml`
(설정, `${LIVE_HOOKS_HOST}`로 훅 주소의 호스트만 compose마다 갈아끼운다)을 만들고
`compose.b-studio.yaml`에도 같은 서비스를 추가했지만, **이번 세션에서는 실제로 뜨지
않았다.**

**해 본 것**:
1. `compose.b-studio.yaml`에 `mediamtx` 서비스를 추가(`LIVE_HOOKS_HOST=commerce`).
2. `restart_service(commerce)`를 호출해 같이 뜨길 기대했다 — b-studio의 `restart_service`는
   `studio.yaml`에 선언된 서비스(commerce·web·consumer-app) 하나만 범위로 재기동하는
   것으로 보인다. `commerce` 컨테이너 안에서 `getent hosts mediamtx`가 풀리지 않았다 —
   그 서비스 자체가 만들어지지 않았다는 뜻이다.
3. `docker` CLI를 컨테이너 안에서 직접 불러 상태를 보거나 수동으로 띄우려 했지만, 이
   샌드박스의 실행 정책이 `docker` 명령 자체를 막는다(앞선 턴들에서 `mysql` CLI가 막힌 것과
   같은 종류의 제약 — "실행 정책에 막힘"으로 즉시 거절됐다). 그래서 이미지를 미리 받아
   기본 설정과 스키마를 맞대 볼 방법도 없었다(`media/mediamtx.yml`의 "버전 주의" 참고).

**추정 원인**: `mysql`·`redis`·`kafka`처럼 `studio.yaml`에 없는 "부가 서비스"는 샌드박스가
**처음 만들어질 때** `compose.b-studio.yaml`을 보고 띄워진 것으로 보이고, 세션 중간에 그
파일에 새 서비스를 추가해도 `restart_service`(스튜디오가 아는 서비스 하나만 재기동)로는
새로 반영되지 않는다 — 전체 샌드박스를 다시 만들어야(다음 세션) 비로소 compose가 다시
읽혀 `mediamtx`가 생길 것으로 본다.

**대신 한 것 — HTTP 훅 경로는 실제로 끝까지 재확인했다**: MediaMTX가 없어도 R1·R2·R3가
실제로 주고받을 HTTP 호출은 전부 직접 재현해 커밋된 코드로 끝까지 확인했다(아래는 실제
실행 결과 — 요약에도 그대로 붙인다):

```
POST /api/v1/live/broadcasts (판매자)
  → 201 상당: {"id":1,"status":"SCHEDULED","streamKey":"01M4D07CMZTJKSG2CT01YA8XB4", ...}

POST /api/v1/live/hooks/auth {"path":"live/01M4D0...","action":"publish"}  → 200
POST /api/v1/live/hooks/auth {"path":"live/WRONGKEY","action":"publish"}   → 401

POST /api/v1/live/hooks/publish -d path=live/01M4D0...  → 200
GET  /api/v1/live/broadcasts/1  → {"status":"LIVE","startedAt":"...613001Z", ...}

POST /api/v1/live/hooks/unpublish -d path=live/01M4D0...  → 200
GET  /api/v1/live/broadcasts/1  → {"status":"LIVE", ...}   # 아직 유예 안 — 안 끝남

(실제로 35초 대기 — app.live.grace-scheduler가 local 프로파일에서 5초 주기로 돈다)

GET  /api/v1/live/broadcasts/1
  → {"status":"ENDED","startedAt":"...613001Z","endedAt":"...26.261748Z"}

POST /api/v1/live/hooks/auth {"path":"live/01M4D0...(ENDED)","action":"publish"} → 401
```

이걸로 R1(생성·키 발급)·R2(인증 통과/거절)·R3(시작 전이·재접속 유예 유지·실제 30초 경과
후 스케줄러가 끝맺음·종료 후 재송출 거절)가 **MediaMTX 없이도 애플리케이션 쪽 계약은
맞게 돈다**는 것을 실제로 확인했다. 못 확인한 건 "MediaMTX가 실제로 RTMP 바이트를 받아
그 훅을 정확한 시점에 부르는가" 하나뿐이다 — 그건 이미지를 실제로 띄워야만 보이는
부분이라, 다음 세션(샌드박스 재생성) 또는 로컬 `docker compose up -d`로 다시 확인해야
한다.

## 다시 볼 조건

- **MediaMTX 훅 엔드포인트(`/api/v1/live/hooks/**`)가 인터넷에 그대로 노출되면** → 누구나
  `publish`/`unpublish`를 직접 쳐서 가짜 상태 전이를 만들 수 있다. 운영 전환 시 리버스
  프록시로 MediaMTX 호스트에서만 오게 막거나, 공유 비밀 헤더를 추가해야 한다(지금은 같은
  네트워크 안이라는 전제뿐이다, `LiveMediaHooksController` 주석 참고).
- **이벤트를 실제로 구독하는 쪽이 생기면**(R7 시청 화면 전환, R31 운영 로그) → 그때
  `@ApplicationModuleListener`를 붙인다. 지금은 발행만 하고 Outbox 테이블에 쌓이는 것만
  보장한다 — 구독자가 없는 이벤트는 쌓이기만 하고 완료 처리(completion)가 안 된다는 점을
  그 단계에서 다시 본다(ADR-002의 "독약" 경고와 같은 종류).
- **방송이 많아져 재접속 유예 스캔(5초 주기)이 느려지면** → `idx_live_broadcast_status_disconnect`
  인덱스가 있지만, 더 커지면 배치 상한(다른 배치들의 `read-chunk-size` 패턴)을 둔다.
- **다른 언어·다른 배포 주기의 전용 알림 서비스가 필요해지면** → Outbox를 Kafka로 외부화하는
  경로가 이미 있다(`spring-modulith-events-kafka`, ADR-002의 "포기한 것"). 지금 당장 Kafka를
  들일 근거는 없다 — 숏폼과 같은 판단이다.
- **MediaMTX를 샌드박스에 띄우지 못했다면**(아래 "인프라" 절 참고) → 실제 운영/로컬 환경에서
  재확인하고, 안 되면 그 원인(네트워크 egress 등)을 여기 추가한다.

## 2026-10-08 추가: 실제 송출 확인에서 찾은 설정 버그 두 개 (`tools/verify-live-broadcast.sh`)

다음 세션에서 샌드박스에 MediaMTX가 실제로 떴다. `tools/verify-live-broadcast.sh`(실제
ffmpeg → RTMP → MediaMTX)로 돌려 보니 R3.1·R3.2·R3.3이 전부 실패했다(PASS=3 FAIL=3) —
사용자가 MediaMTX 컨테이너 로그를 대신 확인해 원인을 짚어 줬다(이 샌드박스는 비관리
서비스의 로그를 볼 도구가 없다).

### 버그 1(확인됨, 고침): `${LIVE_HOOKS_HOST}`는 MediaMTX가 치환하지 않는다

로그: `failed to authenticate: HTTP request failed: parse "http://${LIVE_HOOKS_HOST}:8080/...": invalid character "{" in host name`.

`${...}` 셸 스타일 치환은 **docker compose가 compose 파일 자신의 내용에만** 적용하고,
MediaMTX가 **마운트된 설정 파일의 내용**을 읽을 때는 적용되지 않는다(MediaMTX 자신도 그런
치환을 지원하지 않는다) — 이 둘을 섞어 쓴 것이 원래의 실수였다. 그래서 `authHTTPAddress`
값이 문자 그대로 `${LIVE_HOOKS_HOST}`를 담은 채 MediaMTX에 들어가 모든 인증 요청이
파싱 단계에서 깨졌다. **R2.1("통과")은 그래서 가짜였다** — 틀린 키라서 거절된 게 아니라
인증 자체가 전부 깨져서 거절(또는 무응답)된 것이었다. 검증 스크립트가 "LIVE로 안 바뀜"만
보고 "거절 사유"는 안 봤기 때문에 이 가짜 통과를 못 잡았다(아래 "스크립트 수정"에서 고친다).

**고친 방법**: 호스트가 환경마다 다른 값은 MediaMTX가 **공식으로 지원하는** 환경 변수
덮어쓰기(`MTX_` 접두 + 파라미터 이름 대문자)로 옮긴다 — 설정 파일 자신은 가장 흔한
환경(이 샌드박스, `commerce`)의 리터럴 값을 기본값으로 담고, 다른 호스트가 필요한 환경은
compose의 `environment`에서 덮어쓴다.

- `media/mediamtx.yml`: `authHTTPAddress`·`pathDefaults.runOnReady`·
  `pathDefaults.runOnNotReady`를 `commerce`로 리터럴 고정(이 샌드박스의 기본 흐름).
- 경로별(`"~^live/.+$"`) 훅 설정을 **`pathDefaults`로 옮겼다** — 정규식 경로 이름은
  `MTX_PATHS_<이름>_...` 형태의 유효한 환경 변수 식별자를 만들 수 없어서다(`~`·`^`·`.`·
  `+`·`$`·`/` 전부 식별자에 못 쓴다). `pathDefaults`는 평범한 키라 `MTX_PATHDEFAULTS_
  RUNONREADY`로 바로 덮어쓸 수 있다. 이 MediaMTX 인스턴스는 라이브 방송 전용이라 "모든
  경로에 같은 훅"으로 바꿔도 범위가 넓어지는 문제가 없다 — 보안은 여전히
  `authHTTPAddress`(경로가 `live/`로 시작하지 않으면 `LiveStreamPaths.keyFrom`이 null →
  인증 거절)가 쥔다.
- `compose.yaml`(저장소 루트, `host.docker.internal`이 필요한 환경)은 `MTX_AUTHHTTPADDRESS`·
  `MTX_PATHDEFAULTS_RUNONREADY`·`MTX_PATHDEFAULTS_RUNONNOTREADY`로 덮어쓴다. `$MTX_PATH`는
  MediaMTX 자신이 훅 실행 시점에 채우는 토큰이라, docker compose가 먼저 치환해 버리지
  않도록 compose 파일에서는 `$$MTX_PATH`로 이스케이프했다(이것도 처음엔 놓치기 쉬운
  지점이라 여기 적어 둔다 — `$VAR`·`${VAR}` 둘 다 compose가 자기 환경으로 먼저 치환을
  시도하기 때문에, 그걸 피하려면 `$$`가 필요하다).
- `compose.b-studio.yaml`: 기본값이 이미 `commerce`라 환경 변수 덮어쓰기가 필요 없어졌다 —
  기존 `LIVE_HOOKS_HOST` 변수는 지웠다(더는 아무도 안 읽는다).

**이 수정이 샌드박스에 아직 적용되지 않았다**: MediaMTX는 설정을 시작 시점에만 읽는다(핫
리로드가 안 된다 — 파일을 고친 뒤 `/v3/paths/list`용 포트 9997이 여전히 안 열려 있는
것으로 확인했다, 아래 "버그 2" 참고). 컨테이너를 재시작해야 반영되는데, 이 샌드박스에서는
`restart_service(commerce)`가 `commerce` 하나만 재기동하고 `mediamtx`에는 아무 영향이
없다는 것을 이번에도 다시 확인했다(수정 전후 두 번 다 테스트, 결과 동일). **사람이
`docker compose -f compose.b-studio.yaml restart mediamtx`(또는 재생성)를 해 줘야 이
수정이 실제로 적용된다** — 그 뒤 `tools/verify-live-broadcast.sh`를 다시 돌리면 된다.

### 버그 2(의심, 미확정): 공식 이미지에 셸·`curl`이 없을 수 있다

`runOnReady`/`runOnNotReady`는 MediaMTX가 **셸로 명령 문자열을 실행**해야 동작한다
(`curl ...`이라는 문자열 자체는 MediaMTX가 직접 HTTP 호출을 만드는 게 아니라 `/bin/sh -c`로
넘기는 것으로 알려져 있다). 공식 `bluenviron/mediamtx` 이미지가 최소 구성(scratch류)이면
`/bin/sh`·`curl`이 아예 없어서 훅이 "실행 시도 자체가 실패"할 수 있다. 이 샌드박스는
`docker exec`가 실행 정책에 막혀 이미지 내부를 직접 들여다볼 수 없어 **아직 확인하지
못했다** — 버그 1을 고치고 컨테이너를 재시작한 뒤에도 R3.1이 여전히 실패하면, 이게 원인일
가능성이 크다.

**구분하는 방법(셸 자체의 로그 없이도 가능)**: `api: yes`/`apiAddress: :9997`을 이번에
새로 켰다(위 mediamtx.yml) — 이건 MediaMTX 자신의 HTTP 서버 기능이라 셸이 전혀 필요 없다.
재시작 후:
1. 올바른 키로 ffmpeg 송출을 시작한다.
2. `curl http://mediamtx:9997/v3/paths/list`로 MediaMTX **자신이 보는 상태**를 본다 — 그
   경로가 `"ready": true`로 나오면 RTMP 수신 자체는 정상이다.
3. 그런데도 commerce의 방송 상태가 `LIVE`로 안 바뀌면 — **훅 전달(셸·curl)이 깨진 것으로
   확정**할 수 있다(2가 성공했는데 3이 실패하면, 중간에서 훅 실행만 빠진 것이다).

### 셸·curl이 없을 때의 대안 비교

| 대안 | 셸·curl 필요 | 장점 | 단점 |
|---|---|---|---|
| **A. 지금 방식 유지**(공식 이미지 + 설정만 고침) | 필요 | 변경 없음, 가장 단순 | 셸·curl이 없으면 전혀 안 됨 |
| **B. 커스텀 이미지**(mediamtx 바이너리 위에 셸·curl이 있는 베이스로 다시 빌드) | 불필요(자체 제공) | 훅 방식·코드 전부 유지 | "공식 이미지" 전제가 깨짐, 이미지 빌드·보안 패치를 직접 떠안음 |
| **C. commerce가 Control API(`/v3/paths/list`)를 폴링** | 불필요 | 셸 의존 완전 제거, 이미 켜 둔 API만 쓴다 | 폴링 주기만큼 상태 반영이 늦다(R6 지연 예산에 얹힘), 새 스케줄러 필요 |
| **D. `authHTTPAddress`(네이티브 HTTP, 셸 불필요)의 publish 액션 자체를 "시작" 신호로 겸용** | 불필요 | 시작 신호는 거의 즉시(폴링 지연 없음) | 종료(연결 끊김) 신호는 못 준다 — 끊김 감지는 C가 필요해 단독으로는 못 쓴다 |

**결정(조건부)**: B는 "공식 이미지" 전제를 버리는 비용이 A·C보다 크고, D는 종료 신호가
없어 혼자 못 쓴다. **셸·curl이 없는 것으로 확인되면 C(Control API 폴링)를 채택한다** —
필요하면 D(시작 신호만 선반영)를 C와 함께 보강해 LIVE 전이 지연을 줄인다. 폴링 설계
스케치: `LiveBroadcastGraceScheduler`와 같은 `@ConditionalOnProperty` 게이트를 쓰는 새
스케줄러가 `GET /v3/paths/list`의 `ready` 집합과 DB의 `LIVE` 방송 목록을 맞대 보고,
차이가 생긴 쪽만 **기존** `LiveBroadcastService#handlePublish`/`#handleUnpublish`를
그대로 부른다 — 바뀌는 건 "누가 훅을 거는가"(MediaMTX push → commerce pull)뿐이고 도메인
로직(`LiveBroadcast` 상태 전이, 재접속 유예 스캐너)은 전혀 안 바뀐다.

**아직 구현하지 않은 이유**: 버그 1만 고친 상태로는 R3.1이 여전히 실패하는 게 "버그 1이
안 적용돼서"인지 "버그 2(셸 없음)까지 겹쳐서"인지 구분이 안 된다 — 컨테이너를 재시작해
버그 1 수정을 반영한 뒤, 위 "구분하는 방법"으로 실제로 셸이 문제인지 먼저 확인하고
나서 C를 구현하는 게 순서다(확인 안 된 가정으로 새 스케줄러를 먼저 만들면, 그게 맞는지도
또 MediaMTX 재시작 없이는 못 재는 같은 문제에 걸린다).

## 2026-10-08 추가 2: 셸 없음 확정, Control API 폴링(C) 구현 + 세 번째 버그

사용자가 mediamtx를 재시작해 버그 1 수정을 반영하고 `docker exec mediamtx sh`로 직접 확인해
줬다: `exec: "sh": executable file not found in $PATH`. **공식 이미지에 셸이 없다 —
확정이다.** `runOnReady`/`runOnNotReady`는 처음부터 이 이미지에서 될 수 없는 방법이었다.
위 비교표의 **C(Control API 폴링)를 채택**해 구현했다.

### 세 번째 버그(구현 중 발견): 훅을 pathDefaults로만 옮기면서 `paths:` 선언이 사라졌다

버그 1을 고칠 때 경로별(`"~^live/.+$"`) 블록을 pathDefaults로 완전히 대체했는데, 재시작해
보니 MediaMTX 로그에 `[RTMP] [conn ...] closed: path 'live/...' is not configured`가
찍혔다(인증을 묻기도 전에 거절) — "이 경로 패턴을 받는다"는 선언(`paths:`)과 "그 경로의
기본 동작"(`pathDefaults:`)은 **별개**였다. 훅이 사라졌어도(더는 명령 훅을 안 쓰므로)
경로 패턴 선언 자체는 그대로 필요하다. 지금은 빈 블록으로 되살렸다:

```yaml
paths:
  "~^live/.+$": {}
```

`pathDefaults`는 전부 지웠다(훅이 없으니 거기 적을 것도 없다 — "쓰지 않게 된 명령 훅
설정은 지운다"는 지시대로).

### 구현: `MediaMtxPathPoller` — 기존 도메인 로직은 그대로, 전달 방식만 바뀜

- **`MediaMtxPathsSource`**(인터페이스) / **`RestClientMediaMtxPathsSource`**(구현,
  `GET /v3/paths/list`를 `RestClient`로 읽는다, `CdcHealthMonitor`와 같은 타임아웃 관례) —
  지금 ready(퍼블리셔가 붙어 흐르는 중)인 `live/*` 스트림 키 집합만 돌려준다. 테스트는 이
  인터페이스의 가짜로 바꿔 실제 HTTP 없이 돈다(`ShortsRankingSignals`와 같은 패턴).
- **`MediaMtxPathPoller`**(`@Scheduled`, `app.live.mediamtx-poller.enabled` 게이트 —
  `LiveBroadcastGraceScheduler`와 같은 관례) — 매 주기 ready 집합과 `repository.
  findByStatus(LIVE)`를 맞대 보고 **기존** `LiveBroadcastService#handlePublish`/
  `#handleUnpublish`를 그대로 부른다. 바뀐 건 "누가 거는가"뿐이고, `LiveBroadcast`의 상태
  전이·재접속 유예 스캐너는 전혀 안 바뀌었다.
- **끊김은 처음 알아챈 순간에만 알린다** — `disconnectedAt`이 이미 있는 LIVE 방송은 다시
  `handleUnpublish`를 안 부른다. 이유: `recordDisconnect`는 호출될 때마다 그 시각을
  "지금"으로 덮어쓴다(재접속 감지를 위해 의도된 동작) — 그런데 폴러가 "여전히 안 ready"를
  매 주기 반복해서 `handleUnpublish`를 계속 부르면, `disconnectedAt`이 매번 갱신돼
  **재접속 유예가 영원히 끝나지 않는다.** 폴링이라는 전달 방식 자체가 만드는 함정이라
  폴러 쪽에서 막았다(단위 테스트 `doesNotRefreshDisconnectTimeEveryPoll`).
- 인증(R2)은 안 바뀌었다 — `authHTTPAddress`는 MediaMTX 자신이 만드는 네이티브 HTTP
  호출이라 셸이 필요 없다. 보안 경계는 여전히 거기 있다.

### 폴링 주기와 지연 (정직하게)

`app.live.mediamtx-poller.interval-ms` 기본값은 **2000ms**다. 근거:

- MediaMTX Control API 호출은 로컬 네트워크의 작은 JSON 조회 하나뿐이라 비용이 작다 —
  `grace-scheduler`(재접속 유예 만료만 보는, 덜 급한 점검)의 기본 5000ms보다는 짧게 잡았다.
  이게 지금 **유일한** LIVE/끊김 감지 경로가 됐으니 더 빠듬직해야 한다고 판단했다(가정,
  실측 전).
- **이 지연이 늘어나는 곳**: ①송출 시작부터 commerce가 `LIVE`로 아는 시점까지, ②송출이
  끊긴 시점부터 commerce가 `disconnectedAt`을 기록(재접속 유예 시작)하는 시점까지 — 각각
  최대 폴링 주기만큼(평균 그 절반) 늦어진다. ③ENDED 전이는 영향 없다(끊김 기록 자체가
  늦어지는 만큼만 전체가 밀리고, 유예 만료 판정은 여전히 `grace-scheduler`가 한다).
- **R6(송출~시청 지연 5초 이하)에는 영향이 없다** — 그건 MediaMTX가 RTMP를 받아 HLS로
  내보내는 미디어 경로 자체의 지연이고, 이 폴링은 그 경로를 건드리지 않는다(commerce의
  `LIVE` 상태 갱신이 늦어질 뿐, 시청자의 영상·오디오 수신은 그대로 MediaMTX가 즉시
  처리한다). 다만 "방송이 LIVE임을 알고 채팅·주문을 열어주는" 것 같은 **상태 의존
  기능**(다음 단계)은 이 지연만큼 늦게 열린다 — 그런 기능이 생기면 이 숫자를 다시 본다.
- **아직 실측하지 않았다** — 2000ms는 "grace-scheduler보다 짧게"라는 상식적 판단이고,
  Control API 호출 자체가 실제로 몇 ms 걸리는지, 그 비용이 폴링 주기를 더 줄여도 되는지는
  측정하지 않았다. 다시 볼 조건에 남긴다.

### 다시 볼 조건(추가)

- **폴링 비용이 측정되면** — Control API 호출 시간·CPU를 재서 주기를 더 줄이거나(지연
  개선) 늘릴지(비용 절감) 결정한다.
- **상태 의존 기능(채팅 입장 허용, 고정 상품 주문 등)이 생기면** — 이 폴링 지연이 그
  기능들의 지연 예산에 들어간다는 것을 그 기능의 설계에서 명시한다.
- **MediaMTX가 셸 있는 이미지로 바뀌면**(공식 이미지가 바뀌거나 커스텀 이미지로 전환하면)
  — 그때는 push(훅)로 되돌릴 수 있는지 다시 본다. 지금은 이 폴링이 유일한 경로다.

### 이번 세션에서 아직 재검증 못 함 — 또 컨테이너 재시작이 필요하다

위 세 번째 버그(`paths:` 선언 누락)를 고친 뒤 `tools/verify-live-broadcast.sh`를 다시
돌렸지만, mediamtx 컨테이너는 **사용자가 버그 1만 고친 상태로 재시작해 둔 그대로**였다 —
이번 수정(세 번째 버그 고침 + pathDefaults 제거)은 아직 반영 전이다. 실제로 MediaMTX
Control API가 `{"status":"error","error":"path not found"}`를 돌려줘(예상한 그대로)
RTMP 송출이 인증 이전에 거절됨을 재확인했다:

```
PASS=4 FAIL=1 UNKNOWN=4
사전 점검(commerce 직접 호출 200/401): 통과
R3.1(올바른 키 → LIVE): 실패(15초 타임아웃)
진단(MediaMTX 자신의 ready 상태): unknown(응답: {"status":"error","error":"path not found"})
R2.1·R3.2·R3.3·R2.2: UNKNOWN(배관이 증명 안 돼 판정 보류)
```

`restart_service`로 mediamtx를 재시작하는 기능은(사용자 말대로) 아직 이 세션에 들어오지
않았다 — 시도해 확인했다(`restart_service(service="mediamtx")` → 입력 검증에서 바로
거절, 허용값은 `commerce`·`web`·`consumer-app`뿐). **사람이 다시
`docker compose -f compose.b-studio.yaml restart mediamtx`를 해 줘야** 이번 수정
(파일 전체를 다시 썼다 — `paths:` 복원·`pathDefaults` 제거)이 반영된다. 반영되면
`MediaMtxPathPoller`(커머스 쪽, `app.live.mediamtx-poller.enabled=true`로 이미 떠 있다)가
다음 폴링 주기(기본 2초) 안에 그 상태를 읽어 R3.1부터 다시 통과할 것으로 예상한다.

## 2026-10-08 추가 3: 새 샌드박스에서 끝까지 통과 — R1~R3 실 송출 경로 확정

새 샌드박스에서 mediamtx가 최신 설정(Control API 폴링용 `api: yes`, `paths:
"~^live/.+$": {}` 복원)으로 떴고, 이번에는 `service_logs`/`restart_service`로 mediamtx
자체도 다룰 수 있었다. `tools/verify-live-broadcast.sh`를 돌려 두 가지를 더 고쳤다(둘 다
**스크립트의 판정 로그 자체의 버그**였다 — MediaMTX·commerce 쪽 코드는 이미 맞았다):

1. **HLS 302를 못 따라갔다** — MediaMTX HLS 서버는 첫 요청에 `302` + `Set-Cookie:
   cookieCheck=1`로 응답하고(LL-HLS 세션 확립용 쿠키 체크), 그다음에 실제 재생목록을
   준다. 스크립트가 `-L` 없이 curl을 불러 302 자체를 "실패"로 오판했다 — `-L` 추가로
   고쳤다.
2. **R2.1이 "틀린 키" 대신 "원래 방송"의 상태를 봤다** — R3.1을 먼저 보도록 순서를 바꾼
   뒤에도, 거절 판정 자체는 여전히 원래 방송(`$BROADCAST_ID`, 이미 R3.1에서 LIVE)의 상태를
   봤다. 틀린 키용 ffmpeg는 **별도의 새 RTMP 연결**이라 원래 방송과 무관하고, 그 상태는
   틀린 키가 거절됐는지와 아무 상관이 없다 — 그런데도 "LIVE"로 보여 가짜로 FAIL이 났다.
   판정을 "MediaMTX 자신이 그 틀린 키의 경로를 ready로 보는가"(Control API,
   `mediamtx_path_ready`)로 바꿔 고쳤다.

고친 뒤 **같은 스크립트를 세 번 연달아 돌려 매번 `PASS=10 FAIL=0 UNKNOWN=0`**을 확인했다.
mediamtx·commerce 로그로 교차 확인한 근거(요지, 실제 타임스탬프):

```
08:04:46 [RTMP] conn ... opened                                      ← 올바른 키 송출 시작
08:04:48 [path live/01M4D8...] stream is available and online         ← R3.1: MediaMTX가 받음
08:04:52 [RTMP] conn ... failed to authenticate: server replied 401   ← R2.1: 틀린 키 즉시 거절(MediaMTX 로그)
08:04:52 commerce: 라이브 송출 인증 거절: 키 불일치 key=not-the-real-key-...  (commerce 로그, 같은 사건)
...
08:05:27 commerce: 라이브 방송 재접속 유예 만료로 종료 id=1             ← R3.3: live.ended 발행 경로
08:05:28 commerce: 라이브 송출 인증 거절: 상태 불일치 key=... status=ENDED ← R2.2
```

R1.1·R2.1·R3.1·HLS 수신·R3.2·R3.3·R2.2 전부 **실제 ffmpeg→RTMP→MediaMTX→(Control API
폴링)→commerce** 경로로 확인됐다. 이 ADR의 "다시 볼 조건" 중 "MediaMTX를 샌드박스에
띄우지 못했다면"은 더 이상 유효하지 않다 — 떴고, 끝까지 돈다.
