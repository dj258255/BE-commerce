import { describe, expect, it } from 'vitest';
import type { ShortsFeedItem, ShortsFeedPage } from './contracts';
import {
  appendPage,
  applyFetchResult,
  isPreloaded,
  pickMostVisibleIndex,
  PRELOAD_AHEAD,
  shouldPrefetchNext,
} from './shortsFeed';

function item(id: number): ShortsFeedItem {
  return { id, durationSeconds: 20, width: 1080, height: 1920, createdAt: '2026-01-01T00:00:00Z', products: [] };
}

function page(items: ShortsFeedItem[], nextCursor: number | null, hasNext: boolean): ShortsFeedPage {
  return { items, nextCursor, hasNext };
}

describe('R26.1: 첫 화면 — READY 영상이 보이고 다음 1~2개가 미리 불러와진다', () => {
  it('appendPage가 첫 쪽 항목을 그대로 담는다', () => {
    const merged = appendPage([], page([item(3), item(2), item(1)], 1, true));
    expect(merged.map((i) => i.id)).toEqual([3, 2, 1]);
  });

  it(`isPreloaded: 보이는 항목(activeIndex) 포함 다음 ${PRELOAD_AHEAD}개까지만 "미리 불러온" 것으로 본다`, () => {
    const activeIndex = 0;
    expect(isPreloaded(0, activeIndex)).toBe(true); // 자기 자신(지금 보이는 것)
    expect(isPreloaded(1, activeIndex)).toBe(true);
    expect(isPreloaded(2, activeIndex)).toBe(true);
    expect(isPreloaded(3, activeIndex)).toBe(false); // 3번째는 범위 밖
  });

  it('appendPage는 중복 id를 다시 넣지 않는다(재시도로 같은 쪽이 다시 와도 안전)', () => {
    const first = appendPage([], page([item(1), item(2)], 2, true));
    const retried = appendPage(first, page([item(1), item(2)], 2, true));
    expect(retried.map((i) => i.id)).toEqual([1, 2]);
  });
});

describe('R26.2: 스와이프 — 이전 영상은 멈추고 보이는 영상만 재생된다', () => {
  it('pickMostVisibleIndex: 교차 비율이 가장 큰 항목을 고른다', () => {
    const next = pickMostVisibleIndex(
      [
        { index: 0, isIntersecting: false, intersectionRatio: 0 },
        { index: 1, isIntersecting: true, intersectionRatio: 0.95 },
      ],
      0,
    );
    expect(next).toBe(1); // 스와이프로 1번이 화면을 채우면 활성 인덱스가 옮겨간다
  });

  it('아직 교차하는 항목이 없으면(스크롤 중) 이전 활성 인덱스를 유지한다', () => {
    const next = pickMostVisibleIndex(
      [{ index: 0, isIntersecting: false, intersectionRatio: 0 }],
      2,
    );
    expect(next).toBe(2);
  });

  it('활성 인덱스가 바뀌면 이전 인덱스는 더 이상 "미리 불러온" 범위에 들지 않을 수 있다', () => {
    // activeIndex가 0→3으로 옮겨가면(아래로 여러 번 스와이프) 0번은 더는 preload 대상이 아니다.
    expect(isPreloaded(0, 0)).toBe(true);
    expect(isPreloaded(0, 3)).toBe(false);
  });
});

describe('R26.3: 빈 피드 — 영상이 0건이면 재생 영역을 만들 항목이 없다', () => {
  it('빈 쪽을 받으면 항목이 없다', () => {
    const merged = appendPage([], page([], null, false));
    expect(merged).toHaveLength(0);
  });

  it('applyFetchResult가 성공으로 처리해도 items가 비어 있으면 화면은 빈 상태를 그린다', () => {
    const state = applyFetchResult([], { ok: true, data: page([], null, false) });
    expect(state).toEqual({ status: 'ready', items: [], nextCursor: null, hasNext: false });
  });

  it('비어 있으면(hasNext=false) 다음 쪽을 프리페치하지 않는다', () => {
    expect(shouldPrefetchNext(0, 0, false)).toBe(false);
  });
});

describe('R26.4: 조회 실패 — 오류 문구가 뜨고 재시도하면 복구된다', () => {
  it('실패 응답은 error 상태가 된다', () => {
    const state = applyFetchResult([], { ok: false, error: 'HTTP 500', url: '/api/v1/shorts/feed' });
    expect(state).toEqual({ status: 'error', message: 'HTTP 500', url: '/api/v1/shorts/feed' });
  });

  it('실패 뒤 재시도(같은 함수를 다시 호출)가 성공하면 ready로 돌아온다', () => {
    const afterFailure = applyFetchResult([], { ok: false, error: 'HTTP 500', url: '/api/v1/shorts/feed' });
    expect(afterFailure.status).toBe('error');

    // 재시도 버튼은 이전 items를 그대로 넘겨 다시 호출한다 — 실패 전 상태가 없었으므로 빈 배열.
    const afterRetry = applyFetchResult([], { ok: true, data: page([item(1)], null, false) });
    expect(afterRetry).toEqual({ status: 'ready', items: [item(1)], nextCursor: null, hasNext: false });
  });

  it('"더 불러오기" 실패는 이미 보여준 items를 지우지 않는다', () => {
    const ready = applyFetchResult([], { ok: true, data: page([item(1), item(2)], 2, true) });
    expect(ready.status).toBe('ready');
    const prevItems = ready.status === 'ready' ? ready.items : [];

    const afterLoadMoreFails = applyFetchResult(prevItems, { ok: false, error: 'HTTP 500', url: '/api/v1/shorts/feed?cursor=2' });

    expect(afterLoadMoreFails).toEqual({ status: 'error', message: 'HTTP 500', url: '/api/v1/shorts/feed?cursor=2' });
    // items 자체는 함수 바깥(React 상태)에 남아 있어야 한다는 계약 — prevItems를 그대로 썼는지 확인.
    expect(prevItems.map((i) => i.id)).toEqual([1, 2]);
  });
});
