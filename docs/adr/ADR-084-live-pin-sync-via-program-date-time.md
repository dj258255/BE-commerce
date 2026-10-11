# ADR-084. 고정 상품 카드 동기화는 HLS PROGRAM-DATE-TIME으로, 전달은 Outbox 대신 즉시 WebSocket으로

- 상태: **채택**
- 날짜: 2026-10-08
- 관련: R8·R9, [42-라이브커머스-숏폼-명세.md](../42-라이브커머스-숏폼-명세.md) §5,
  [ADR-002](ADR-002-outbox-event-publication-registry.md)(Outbox),
  [ADR-043](ADR-043-home-impression-log.md)(관측 vs 도메인 이벤트),
  [ADR-082](ADR-082-live-broadcast-outbox-hooks.md)(R1~R3, MediaMTX 실 송출 확인)

## 맥락

R9는 "시청자의 재생 시점이 고정 이벤트의 `effectiveAt`에 도달한 뒤에만 카드를 갱신"하라고
요구한다 — 영상보다 먼저 가격이 바뀌어 보이면 안 된다. 이걸 지키려면 클라이언트가 **"지금
화면에 보이는 장면이 실제로 몇 시인가"**를 알아야 한다. HLS는 재생 위치(`video.currentTime`)
를 세그먼트 상대 시간으로만 주고, 서버 시각과 바로 비교할 수 있는 값을 기본으로 주지 않는다.

## 결정 1 — 재생 시점↔서버 시각: HLS `#EXT-X-PROGRAM-DATE-TIME` + hls.js `FRAG_CHANGED`

**매핑 방법**: 각 HLS 세그먼트(프래그먼트)는 `#EXT-X-PROGRAM-DATE-TIME`으로 그 세그먼트가
시작하는 **실제 벽시계 시각**(ISO-8601)을 실어 나른다. hls.js는 재생 중인 프래그먼트가
바뀔 때마다 `Hls.Events.FRAG_CHANGED`를 쏘고, 그 `frag.programDateTime`(epoch ms)과
`frag.start`(그 프래그먼트가 HLS 내부 타임라인에서 시작하는 초)를 그대로 읽을 수 있다.
그러면:

```
지금 보이는 장면의 실제 시각 = frag.programDateTime + (video.currentTime − frag.start) × 1000
```

이 계산(`lib/livePin.ts`의 `estimatePlaybackWallClockMs`)이 추정한 값이 이벤트의
`effectiveAt`에 도달한 뒤에만 카드를 바꾼다(`tickLivePinSync`, `timeupdate`마다 재계산) —
웹 단위 테스트(`lib/livePin.test.ts`)가 "도달 전엔 안 바뀌고 도달 후엔 바뀐다"를 결정적으로
확인한다(R9.1).

**MediaMTX가 이 태그를 실제로 내는지 확인(완료)**: `tools/verify-live-pin-events.sh`로
실제 RTMP 송출 중 확인했다 — **있다**. 단, 처음 시도에서 마스터(멀티베리언트) 재생목록
(`index.m3u8`)만 보고 "없다"고 오판했다 — `#EXT-X-PROGRAM-DATE-TIME`은 마스터가 아니라
그게 가리키는 **렌디션(미디어) 재생목록**(`video1_stream.m3u8?session=...`)에만 찍힌다.
실제로 받은 렌디션 재생목록 한 줄:

```
#EXT-X-PROGRAM-DATE-TIME:2026-10-08T17:45:44.677Z
```

hls.js는 마스터 URL만 넘겨줘도 내부적으로 렌디션을 골라 로드하므로(`LiveViewer.tsx`가
`index.m3u8`을 그대로 쓴다) 이 구분은 **검증 스크립트의 실수였지, 화면 코드가 고칠 건
없었다** — 처음엔 "다시 볼 조건"에 불확실로 남겨 뒀다가, 스크립트를 고친 뒤 확정했다.

**왜 "서버가 먼저 재생 시점을 알려주고 그때 보내는" 방식이 아닌가(대안 비교)**:

| 대안 | 문제 |
|---|---|
| 서버가 모든 시청자의 재생 위치를 추적해 "지금 보여도 되는 시청자에게만" 개별 발송 | 시청자마다 버퍼링·네트워크로 재생 위치가 다 다르다 — 서버가 그걸 정확히 알 방법이 없다(클라이언트가 자기 재생 위치를 보고하게 하면 그 보고 자체가 또 지연·순서 문제를 만든다) |
| effectiveAt 없이 "받는 즉시 적용" | R9가 명시적으로 금지한 동작이다(영상보다 먼저 바뀌어 보임) |
| **PROGRAM-DATE-TIME 기반 클라이언트 추정(채택)** | 클라이언트가 **자기 자신의 재생 파이프라인**(버퍼링 포함)을 거친 뒤의 실제 재생 시점을 안다 — 서버는 "언제 바뀌어야 하는가"만 정하고, "지금이 그 때인가"는 각 클라이언트가 스스로 판단한다. 서버 쪽 추가 상태가 없다 |

## 결정 2 — 전달: Outbox가 아니라 즉시 WebSocket 발행

고정·해제·가격 변경은 **Outbox(Event Publication Registry)에 태우지 않고** 트랜잭션 커밋
후 바로 `LivePinBroadcaster`(WebSocket)로 내보낸다(`LivePinService`). ADR-043의 판단과
같은 이유다 — 이건 **도메인 상태가 아니라 "지금 화면이 무엇을 보여줘야 하는가"라는
관측·전달**이다. Outbox가 보장하는 "반드시 한 번은 처리된다"는 여기서 의미가 다르다 —
방송 중 시청자는 계속 붙어 있으므로 유실돼도 **R9.2(재연결 스냅샷)가 스스로 복구한다**.
그 복구 장치가 이미 요구사항에 있다는 것 자체가 "유실을 영원히 막아야 하는 이벤트가
아니다"라는 신호로 읽었다.

| 대안 | 왜 버렸나 |
|---|---|
| Outbox + 인앱 컨슈머가 WebSocket으로 재발행 | 한 단계(DB 커밋→폴링/리스너→WebSocket)가 늘어 지연이 생긴다 — effectiveAt이 "영상보다 늦게"를 요구하는데, 발행 경로 자체가 늦어지면 그 여유를 깎아먹는다. 유실 보장도 R9.2가 이미 대신한다 |
| Kafka로 외부화 | 브로커 장애 시 지금 당장 켤 이유가 없는 의존을 추가한다(ADR-082·ADR-043과 같은 판단) |

## 왜 push(WebSocket)인가 — pull(폴링)이 아니라

R3(송출 시작·종료)는 MediaMTX 셸이 없어 폴링으로 바꿨다(ADR-082). 고정 이벤트는 그와 다르게
**판매자의 명시적 행동**(상품 고정 버튼 클릭)이 원인이고, 그 행동은 commerce 자신의 REST
API 호출이다 — 외부 프로세스(MediaMTX)의 상태를 밖에서 당겨 와야 하는 문제가 아니다. 그래서
여기는 "이벤트가 생기는 순간 아는 쪽"(commerce)이 "내보내는 쪽"과 같은 프로세스라 push가
그냥 더 단순하고 더 빠르다 — 폴링으로 바꿀 이유가 없다.

## 구현 요약

- **R8**: `LivePin`(방송당 한 행, `uk_live_pins_broadcast`)이 "동시에 고정된 상품은 항상
  1개"를 저장 구조로 강제한다 — 별도 "이전 고정 해제" 호출 없이 `pin()`이 그 자리를 덮어쓴다.
- **R9**: `LivePinEventView(seq, effectiveAt, ...)`를 고정·해제·가격 변경마다
  `LivePinBroadcaster`(실제로는 `LivePinWebSocketHandler`)로 내보낸다. 접속(최초 입장·
  재연결 구분 없음)마다 `LivePinSnapshotReader.snapshot()`을 즉시 보낸다(R9.2) — 그
  스냅샷의 `effectiveAt`은 이미 지난 시각이라 클라이언트의 시간 게이트를 자연히 통과한다
  (별도 "스냅샷 타입" 분기가 필요 없다).
- 상품 이름·실존 확인은 `live.ProductLookup`(media가 정의한 포트, shorts의 같은 이름 포트와
  별개)로만 한다 — 구현(`LiveProductLookupAdapter`)은 commerce 쪽에 있다.
- 수량 선점·주문 확정(R10~R15)은 이번 범위가 아니다 — `remainingQuantity`는 지금 판매자가
  건 한정 수량 그대로다(아직 아무것도 빼지 않는다).

## 2026-10-09 추가: 시청 경로의 스트림 키 노출을 고쳤다(보안 수정)

위 "대가" 1번(시청 페이지 `hlsUrl`에 스트림 키 원문)을 실제로 고쳤다 — R1.2가 다른 판매자에게
막는 것과 같은 위험(그 키를 아는 아무나 방송에 대신 송출)이 "시청자 전체"로 열려 있던 것을
방치할 수 없었다.

### 결정 3 — 경로는 방송 공개 id, 비밀은 RTMP 쿼리로 분리한다

MediaMTX 경로를 `live/{streamKey}`에서 **`live/{broadcastId}`**로 바꿨다 — 숫자 id는 그
자체로 아무 권한도 없어 시청 화면에 노출돼도 안전하다. 송출 자격증명(스트림 키)은 경로가
아니라 RTMP URL의 **쿼리 문자열**로 따로 보낸다:

```
송출:  rtmp://<host>:1935/live/{broadcastId}?pass={streamKey}
시청:  http://<host>:8888/live/{broadcastId}/index.m3u8   (비밀 없음)
```

**MediaMTX가 공식으로 지원하는 방식인가**: 그렇다 — `authHTTPAddress`가 호출하는 HTTP 인증
훅의 요청 바디에는 처음부터 `ip`·`user`·`password`·`path`·`protocol`·`id`·`action`·
`query` 필드가 있다(`MediaMtxAuthRequest`의 예전 주석이 이미 이 필드들을 적어 뒀었다 —
`password`·`query`가 바로 "경로 아닌 다른 통로로 온 비밀"을 받는 자리다). RTMP URL에
`?pass=...`를 붙이면 MediaMTX가 그 값을 연결 시점에 받아 인증 훅 호출 때 `password`
필드(또는 설정에 따라 원문 `query`에만)로 실어 보낸다 — `LiveMediaHooksController.auth`가
`password`가 비어 있으면 `query`에서 직접 `pass=`를 뽑는 방어적 fallback을 둬서, 어느
쪽으로 오든 받는다. **실제로 어느 쪽으로 오는지는 `tools/verify-live-broadcast.sh`로 실
RTMP 송출을 거쳐 확인했다**(요약 참고) — 추측이 아니라 실측이다.

### 버린 대안

| 대안 | 왜 버렸나 |
|---|---|
| **별도 "재생 전용 토큰"을 발급해 HLS 경로에만 넣고, RTMP 경로는 스트림 키 유지** | 토큰 발급·만료·회전 관리가 새로 필요하다 — 결국 "읽기 전용 식별자를 하나 더 만드는" 일인데, 방송 **id 자체**가 이미 그 역할(아무 권한 없는 공개 식별자)을 할 수 있어 더 간단하다 |
| **MediaMTX 앞에 리버스 프록시를 둬서 재생 요청 URL을 재작성** | 새 인프라 컴포넌트가 하나 늘어난다 — 이 단계 범위를 넘는다(ADR-081·082의 "최소로 시작" 원칙과 충돌) |
| **경로는 그대로 두고 시청 페이지만 키를 가리기(마스킹)** | 눈속임일 뿐이다 — 네트워크 탭·HLS 요청을 보면 그대로 드러난다. 실제 권한 분리가 아니라 UI만 숨기는 것 |

### 바뀐 것(코드)

- `LiveStreamPaths.broadcastIdFrom(path)`(기존 `keyFrom`)이 이제 **숫자 id**를 돌려준다.
  `pathFor(id)`가 그 반대(id → 경로 문자열)를 만든다.
- `LiveBroadcastService#authenticatePublish(path, providedSecret)` — 비밀을 두 번째
  인자로 **따로** 받는다. 비교는 `MessageDigest.isEqual`(상수 시간 비교, 타이밍 사이드채널
  완화)로 하고, 거절 로그에는 **방송 id만** 남긴다(스트림 키는 로그에도 안 남는다 — 일부만
  보여줄 필요조차 없게 아예 안 보여준다).
- `handlePublish`/`handleUnpublish`(훅)·`MediaMtxPathPoller`(폴링) 전부 조회를
  `findByStreamKey` → `findById`로 바꿨다 — 이 훅들은 **인증을 이미 통과한 뒤**에만 오므로
  비밀이 더 필요 없다.
- `LivePlaybackController`의 `hlsUrl`이 `broadcast.getId()`로 조립된다(예전엔
  `getStreamKey()`).
- `MediaMtxAuthRequest`에 `password`·`query` 필드를 추가했다(그 전엔 안 쓰고 선언도 안 함).

### 다시 볼 조건(추가)

- **비밀 비교가 상수 시간이어도, HTTP 요청 자체의 응답 시간차(DB 조회 여부 등)로 "이 id에
  방송이 있는가"가 새는 것까지는 안 막았다** — 지금은 방송 id가 순차 발급(IDENTITY)이라
  추측이 어렵지 않다는 점도 있어, 우선순위가 낮다고 보고 남겨 둔다.
- **쿼리 문자열의 비밀이 RTMP 연결 로그(MediaMTX 자신의 액세스 로그 등)에 남을 수 있다** —
  이번 수정은 "commerce 쪽 로그·API 응답·시청 화면"에서의 노출만 막았다. MediaMTX 자체
  로그 레벨·보존 정책은 이번 범위 밖이다.

## 대가·다시 볼 조건 (정직하게, 기존)

1. ~~시청 페이지의 `hlsUrl`에 스트림 키 원문이 그대로 들어간다~~ → **고쳤다**(위
   "2026-10-09 추가" 절 참고).
2. **b-studio 샌드박스에서는 브라우저가 MediaMTX에 직접 닿을 수 없다** — MediaMTX는
   studio.yaml 관리 서비스가 아니라 브라우저가 열 수 있는 미리보기 주소가 없다(포트도 샌드박스
   컴포즈에 publish돼 있지 않다). 그래서 `/live/{id}` 화면은 이 샌드박스에서 **실제 영상
   재생은 안 된다** — WebSocket 연결·고정 이벤트 수신·카드 갱신 로직은 (브라우저가 아니라)
   `tools/verify-live-pin-events.sh`로 실제 MediaMTX+commerce 경로를 거쳐 확인했다(요약
   참고). 로컬 `docker compose`(저장소 루트 `compose.yaml`, 포트 8888 publish됨)에서는
   실제로 재생된다.
3. **Next.js rewrite가 WebSocket 업그레이드를 그대로 통과시키는지 완전히 확정하지 못했다** —
   `next.config.ts`의 `/api/:path*` rewrite 규칙이 HTTP는 확실히 대신 불러 주지만, self-hosted
   Next.js가 WS 업그레이드 요청도 투명하게 중계하는지는 이번 턴에서 브라우저로 직접 재보지
   않았다(위 2번과 같은 이유로 브라우저 재생 확인 자체가 샌드박스에서 막혀 있다). 문제가
   생기면 클라이언트가 commerce 주소로 직접 붙게 바꾸거나(CORS·호스트 노출 필요), Next
   커스텀 서버로 전환해야 한다.
4. ~~PROGRAM-DATE-TIME이 실제로 나오는지~~ → **확인 완료**(위 "결정 1" 참고, MediaMTX
   v1.21.1 기준). 다른 MediaMTX 버전으로 올릴 때는 `tools/verify-live-pin-events.sh`를
   다시 돌려 그 버전에서도 여전히 찍히는지 재확인한다.
5. **`LivePin` 행 보존 정책이 없다** — ENDED된 방송의 행도 그대로 남는다. 지금 규모에서는
   문제가 안 되지만(홈 노출 로그 같은 고빈도 관측이 아니다), 방송 수가 많아지면 ADR-043의
   보존 정책과 같은 장치를 고려한다.
