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

describe('R13.1·R14.1: QUANTITY_CHANGED(한정 수량 갱신·매진)는 즉시 적용된다', () => {
  it('R13.1: 남은 수량 갱신은 재생 시점(effectiveAt)을 기다리지 않고 받는 즉시 카드에 반영된다', () => {
    const pinnedState = tickLivePinSync(
      receiveLivePinEvent(INITIAL_LIVE_PIN_SYNC_STATE, pinnedEvent(1, '2025-12-31T23:59:00.000Z')),
      Date.parse('2026-01-01T00:00:00.000Z'),
    );
    expect(pinnedState.card?.remainingQuantity).toBe(50);

    // effectiveAt이 미래라도(아직 영상이 거기 도달 못 해도) 즉시 바뀌어야 한다.
    const quantityEvent = pinnedEvent(1, '2099-01-01T00:00:00.000Z', {
      type: 'QUANTITY_CHANGED',
      productName: null,
      price: null,
      remainingQuantity: 49,
    });
    const next = receiveLivePinEvent(pinnedState, quantityEvent);

    expect(next.card?.remainingQuantity).toBe(49); // tick 없이도 바로 반영
    expect(next.card?.productName).toBe('P1');     // 상품 이름·가격은 그대로 유지(이벤트에 없음)
    expect(next.card?.price).toBe(9_900);
  });

  it('R14.1: 같은 드롭 안의 여러 QUANTITY_CHANGED가 같은 seq를 써도(서버 재사용) 전부 적용된다 '
      + '— seq 역행 검사(R9.3)는 이 타입에 적용하지 않는다', () => {
    let state = tickLivePinSync(
      receiveLivePinEvent(INITIAL_LIVE_PIN_SYNC_STATE, pinnedEvent(1, '2025-12-31T23:59:00.000Z')),
      Date.parse('2026-01-01T00:00:00.000Z'),
    );
    expect(state.lastAppliedSeq).toBe(1);

    for (const remaining of [49, 48, 1, 0]) {
      state = receiveLivePinEvent(
        state,
        pinnedEvent(1, '2099-01-01T00:00:00.000Z', {
          type: 'QUANTITY_CHANGED',
          productName: null,
          price: null,
          remainingQuantity: remaining,
        }),
      );
    }

    expect(state.card?.remainingQuantity).toBe(0); // 매진까지 전부 반영됐다 — 중간에 하나도 안 걸러짐
    expect(state.lastAppliedSeq).toBe(1); // PINNED/PRICE_CHANGED 계열의 seq 계보는 그대로다
  });

  it('R14.1: 매진(남은 수량 0)이면 카드가 "매진" 상태를 표현한다(remainingQuantity===0)', () => {
    const state = receiveLivePinEvent(
      tickLivePinSync(
        receiveLivePinEvent(INITIAL_LIVE_PIN_SYNC_STATE, pinnedEvent(1, '2025-12-31T23:59:00.000Z')),
        Date.parse('2026-01-01T00:00:00.000Z'),
      ),
      pinnedEvent(1, '2099-01-01T00:00:00.000Z', {
        type: 'QUANTITY_CHANGED',
        productName: null,
        price: null,
        remainingQuantity: 0,
      }),
    );

    expect(state.card?.remainingQuantity).toBe(0);
  });

  it('아직 카드가 없을 때(스냅샷 전) QUANTITY_CHANGED가 오면 아무것도 만들지 않는다 — 다음 스냅샷을 기다린다', () => {
    const state = receiveLivePinEvent(
      INITIAL_LIVE_PIN_SYNC_STATE,
      pinnedEvent(1, '2026-01-01T00:00:00.000Z', {
        type: 'QUANTITY_CHANGED',
        productName: null,
        price: null,
        remainingQuantity: 10,
      }),
    );

    expect(state.card).toBeNull();
  });
});

describe('estimatePlaybackWallClockMs: HLS PROGRAM-DATE-TIME 기반 재생 시점 추정(ADR-084)', () => {
  it('프래그먼트 시작 시각에 (지금 재생 위치 - 프래그먼트 시작 위치)를 더한다', () => {
    const fragmentProgramDateTimeMs = Date.parse('2026-01-01T00:00:00.000Z');
    const estimated = estimatePlaybackWallClockMs(fragmentProgramDateTimeMs, 100 /* frag.start */, 103.5 /* currentTime */);

    expect(estimated).toBe(fragmentProgramDateTimeMs + 3_500);
  });
});
