/**
 * 고정 상품 카드 동기화(R9) — DOM·WebSocket·hls.js를 모르는 순수 로직.
 *
 * 화면(컴포넌트)은 이 함수들을 불러 상태를 바꾸기만 한다(`lib/shortsFeed.ts`와 같은 이유 —
 * 네트워크·재생기 목 없이 테스트할 수 있다).
 *
 * 동기화 규칙은 두 단계다:
 * 1. **seq 게이트**(수신 즉시) — 이미 처리한 seq보다 작거나 같으면 통째로 버린다(R9.3).
 * 2. **시간 게이트**(매 tick) — seq를 통과한 이벤트도 재생 시점(영상의 벽시계 추정치)이
 *    `effectiveAt`에 도달하기 전에는 "대기 중"으로만 쌓아 두고 카드를 바꾸지 않는다(R9.1) —
 *    영상보다 먼저 가격이 바뀌어 보이면 안 되기 때문이다. 재연결 스냅샷(R9.2)은 서버가
 *    이미 지난 시각을 `effectiveAt`으로 주므로, 다음 tick에서 조건을 자연히 만족해
 *    별도 분기 없이 즉시 적용된다.
 */

export type LivePinEventType = 'PINNED' | 'UNPINNED' | 'PRICE_CHANGED';

/** 서버 `LivePinEventView`와 같은 모양. */
export type LivePinEvent = {
  broadcastId: number;
  type: LivePinEventType;
  productId: number | null;
  productName: string | null;
  price: number | null;
  remainingQuantity: number | null;
  seq: number;
  effectiveAt: string; // ISO-8601
};

/** 화면에 지금 보여야 하는 카드 — 고정된 상품이 없으면 null. */
export type PinCard = {
  productId: number;
  productName: string;
  price: number;
  remainingQuantity: number;
};

export type LivePinSyncState = {
  /** 마지막으로 **적용한**(화면에 반영한) seq — 아직 아무것도 못 받았으면 -1. */
  lastAppliedSeq: number;
  card: PinCard | null;
  /** seq는 통과했지만 아직 effectiveAt에 도달하지 않아 기다리는 이벤트. */
  pending: LivePinEvent | null;
};

export const INITIAL_LIVE_PIN_SYNC_STATE: LivePinSyncState = {
  lastAppliedSeq: -1,
  card: null,
  pending: null,
};

function toCard(event: LivePinEvent): PinCard | null {
  if (event.type === 'UNPINNED' || event.productId == null) return null;
  return {
    productId: event.productId,
    productName: event.productName ?? '',
    price: event.price ?? 0,
    remainingQuantity: event.remainingQuantity ?? 0,
  };
}

/** R9.3: `seq`가 이미 적용한 값보다 작거나 같으면(역행·재전달) 무시해야 하는 이벤트다. */
export function isStaleEvent(lastAppliedSeq: number, event: LivePinEvent): boolean {
  return event.seq <= lastAppliedSeq;
}

/**
 * WebSocket 메시지 수신 — seq 게이트만 본다(R9.3). 통과하면 "대기 중"으로 교체한다(아직 처리
 *못 한 더 오래된 대기 이벤트가 있어도, 더 최신 seq가 왔다는 것 자체가 그 이벤트를 대신한다).
 */
export function receiveLivePinEvent(state: LivePinSyncState, event: LivePinEvent): LivePinSyncState {
  if (isStaleEvent(state.lastAppliedSeq, event)) {
    return state; // R9.3: 역행 이벤트는 카드 표시를 바꾸지 않는다
  }
  return { ...state, pending: event };
}

/** R9.1: 재생 시점의 추정 벽시계가 `effectiveAt`에 도달했는가. */
export function hasReachedEffectiveTime(estimatedPlaybackWallClockMs: number, effectiveAtIso: string): boolean {
  return estimatedPlaybackWallClockMs >= Date.parse(effectiveAtIso);
}

/**
 * 매 재생 시점 갱신(`timeupdate`)마다 부른다 — 대기 중인 이벤트가 있고 그 `effectiveAt`에
 * 재생 시점이 도달했으면 그제서야 카드를 바꾼다(R9.1). 재연결 스냅샷(R9.2)의 `effectiveAt`은
 * 이미 지난 시각이라 받은 뒤 첫 tick에서 바로 적용된다.
 */
export function tickLivePinSync(state: LivePinSyncState, estimatedPlaybackWallClockMs: number): LivePinSyncState {
  if (state.pending == null) return state;
  if (!hasReachedEffectiveTime(estimatedPlaybackWallClockMs, state.pending.effectiveAt)) {
    return state;
  }
  return { lastAppliedSeq: state.pending.seq, card: toCard(state.pending), pending: null };
}

/**
 * HLS PROGRAM-DATE-TIME 기반 "지금 재생 중인 프레임의 실제 시각" 추정(ADR-084) — hls.js가
 * `Hls.Events.FRAG_CHANGED`에서 주는 `frag.programDateTime`(그 프래그먼트 시작의 벽시계,
 * epoch ms)과 `frag.start`(HLS 내부 타임라인 기준 시작 초)를 기준으로, 지금
 * `video.currentTime`까지 지난 만큼을 더한다.
 */
export function estimatePlaybackWallClockMs(
  fragmentProgramDateTimeMs: number,
  fragmentStartSeconds: number,
  currentTimeSeconds: number,
): number {
  return fragmentProgramDateTimeMs + (currentTimeSeconds - fragmentStartSeconds) * 1000;
}
