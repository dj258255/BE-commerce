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

## 대가·다시 볼 조건 (정직하게)

1. **시청 페이지의 `hlsUrl`에 스트림 키 원문이 그대로 들어간다**(`LivePlaybackController`).
   MediaMTX가 송출 경로와 재생 경로를 구분하지 않는 지금 구성의 한계다 — 원래 스트림 키는
   "다른 판매자에게도 안 보여야 하는" 송출 자격증명(R1.2)인데, 시청 페이지가 그걸 그대로
   담아 비로그인 포함 모든 시청자에게 노출한다. 운영 전환 전에 재생 전용 토큰(또는 MediaMTX
   앞단 리버스 프록시의 별도 서명 URL)으로 바꿔야 한다 — 지금은 범위 밖(R5·R6 시청 인프라
   본 작업에서 다룬다)이라 이 ADR에 한계로만 남긴다.
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
