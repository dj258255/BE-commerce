import { describe, expect, it } from 'vitest';
import {
  estimatePlaybackWallClockMs,
  hasReachedEffectiveTime,
  INITIAL_LIVE_PIN_SYNC_STATE,
  isStaleEvent,
  receiveLivePinEvent,
  tickLivePinSync,
  type LivePinEvent,
} from './livePin';

function pinnedEvent(seq: number, effectiveAtIso: string, overrides: Partial<LivePinEvent> = {}): LivePinEvent {
  return {
    broadcastId: 1,
    type: 'PINNED',
    productId: 1,
    productName: 'P1',
    price: 9_900,
    remainingQuantity: 50,
    seq,
    effectiveAt: effectiveAtIso,
    ...overrides,
  };
}

describe('R9.1: 재생 시점이 effectiveAt에 도달한 뒤에만 카드가 바뀐다', () => {
  it('R9.1: 가격 변경 이벤트(seq=7, effectiveAt=T)를 받아도 재생 시점이 T 전이면 카드는 그대로다', () => {
    const event = pinnedEvent(7, '2026-01-01T00:00:10.000Z', { type: 'PRICE_CHANGED', price: 7_900 });
    let state = receiveLivePinEvent(INITIAL_LIVE_PIN_SYNC_STATE, event);

    state = tickLivePinSync(state, Date.parse('2026-01-01T00:00:09.000Z')); // 아직 도달 전

    expect(state.card).toBeNull(); // 영상보다 먼저 가격이 바뀌어 보이지 않는다
    expect(state.pending).not.toBeNull();
  });

  it('R9.1: 재생 시점이 effectiveAt(T)에 도달한 뒤에는 7,900원으로 바뀐다', () => {
    const event = pinnedEvent(7, '2026-01-01T00:00:10.000Z', { type: 'PRICE_CHANGED', price: 7_900 });
    let state = receiveLivePinEvent(INITIAL_LIVE_PIN_SYNC_STATE, event);

    state = tickLivePinSync(state, Date.parse('2026-01-01T00:00:10.000Z')); // 정확히 도달

    expect(state.card?.price).toBe(7_900);
    expect(state.lastAppliedSeq).toBe(7);
    expect(state.pending).toBeNull();
  });

  it('hasReachedEffectiveTime: 추정 벽시계가 effectiveAt보다 이르면 false, 그 이후면 true다', () => {
    expect(hasReachedEffectiveTime(Date.parse('2026-01-01T00:00:09.999Z'), '2026-01-01T00:00:10.000Z')).toBe(false);
    expect(hasReachedEffectiveTime(Date.parse('2026-01-01T00:00:10.001Z'), '2026-01-01T00:00:10.000Z')).toBe(true);
  });
});

describe('R9.2: 재연결하면 받는 현재 스냅샷은(이미 지난 effectiveAt) 받는 즉시 적용된다', () => {
  it('R9.2: 상품 P1·7,900원·남은 수량 23 스냅샷(seq=9)을 받으면 다음 tick에서 바로 카드에 반영된다', () => {
    const snapshot = pinnedEvent(9, '2025-12-31T23:59:00.000Z', {
      productName: 'P1',
      price: 7_900,
      remainingQuantity: 23,
    });
    let state = receiveLivePinEvent(INITIAL_LIVE_PIN_SYNC_STATE, snapshot);

    state = tickLivePinSync(state, Date.parse('2026-01-01T00:00:00.000Z')); // 스냅샷 수신 직후 아무 때나

    expect(state.card).toEqual({ productId: 1, productName: 'P1', price: 7_900, remainingQuantity: 23 });
    expect(state.lastAppliedSeq).toBe(9);
  });
});

describe('R9.3: seq가 거꾸로 온 이벤트는 무시한다', () => {
  it('R9.3: seq=7까지 처리한 상태에서 seq=5 이벤트가 늦게 도착하면 무시되고 카드가 바뀌지 않는다', () => {
    const already = pinnedEvent(7, '2026-01-01T00:00:10.000Z', { price: 9_900 });
    let state = receiveLivePinEvent(INITIAL_LIVE_PIN_SYNC_STATE, already);
    state = tickLivePinSync(state, Date.parse('2026-01-01T00:00:10.000Z')); // seq=7 적용 완료
    expect(state.card?.price).toBe(9_900);

    const stale = pinnedEvent(5, '2026-01-01T00:00:05.000Z', { price: 1_000 }); // seq가 거꾸로
    const afterStale = receiveLivePinEvent(state, stale);
    const afterTick = tickLivePinSync(afterStale, Date.parse('2026-01-01T00:00:20.000Z'));

    expect(afterTick.card?.price).toBe(9_900); // 그대로 — 1,000원으로 바뀌지 않았다
    expect(afterTick.lastAppliedSeq).toBe(7);
  });

  it('R9.3: isStaleEvent — seq가 이미 적용한 값과 같아도(중복 재전달) 오래된 것으로 본다', () => {
    const event = pinnedEvent(7, '2026-01-01T00:00:10.000Z');
    expect(isStaleEvent(7, event)).toBe(true);
    expect(isStaleEvent(6, event)).toBe(false);
  });
});

describe('estimatePlaybackWallClockMs: HLS PROGRAM-DATE-TIME 기반 재생 시점 추정(ADR-084)', () => {
  it('프래그먼트 시작 시각에 (지금 재생 위치 - 프래그먼트 시작 위치)를 더한다', () => {
    const fragmentProgramDateTimeMs = Date.parse('2026-01-01T00:00:00.000Z');
    const estimated = estimatePlaybackWallClockMs(fragmentProgramDateTimeMs, 100 /* frag.start */, 103.5 /* currentTime */);

    expect(estimated).toBe(fragmentProgramDateTimeMs + 3_500);
  });
});
