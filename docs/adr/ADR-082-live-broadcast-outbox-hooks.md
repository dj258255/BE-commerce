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
