/**
 * 숏폼 피드(R26) 화면이 쓰는 순수 로직 — DOM·네트워크를 모른다.
 *
 * 컴포넌트(`ShortsFeed`)는 이 함수들을 불러 상태를 바꾸기만 한다. 이렇게 떼어 두면 JSDOM·
 * IntersectionObserver 목 없이 테스트할 수 있다(백엔드의 `ShortsFeedPage.assemble`과 같은 이유).
 */
import type { Result } from './api';
import type { ShortsFeedItem, ShortsFeedPage } from './contracts';

/**
 * 피드 조회 경로. 여기 두는 이유: 클라이언트 컴포넌트(`ShortsFeed`)가 "더 불러오기"에 이 상수가
 * 필요한데, `lib/api.ts`는 `next/headers`를 불러 서버 전용이다 — 클라이언트 컴포넌트가 그 파일에서
 * **값**을 하나라도 가져오면(타입만이면 괜찮다) 번들에 통째로 끼어 빌드가 깨진다. 이 파일은
 * DOM·서버 API를 안 써서 양쪽 다 안전하다.
 */
export const SHORTS_FEED_PATH = '/api/v1/shorts/feed';

/** 화면에 보이는 항목 기준으로 몇 개 앞까지 "미리 불러온 것"으로 볼지(R26: "다음 1~2개 preload"). */
export const PRELOAD_AHEAD = 2;

/** 끝에서 몇 개 남았을 때 다음 쪽을 미리 당겨 받을지. */
export const PREFETCH_BEFORE_END = 2;

/** 새로 받은 쪽을 기존 목록에 이어붙인다. 같은 id가 이미 있으면 중복으로 넣지 않는다(재시도 대비). */
export function appendPage(existing: ShortsFeedItem[], page: ShortsFeedPage): ShortsFeedItem[] {
  const seen = new Set(existing.map((item) => item.id));
  const merged = existing.slice();
  for (const item of page.items) {
    if (!seen.has(item.id)) {
      merged.push(item);
      seen.add(item.id);
    }
  }
  return merged;
}

/** 지금 보이는 인덱스(activeIndex)에서, 끝이 가까워져 다음 쪽을 당겨 받아야 하는가. */
export function shouldPrefetchNext(activeIndex: number, totalItems: number, hasNext: boolean): boolean {
  if (!hasNext || totalItems === 0) return false;
  return activeIndex >= totalItems - 1 - PREFETCH_BEFORE_END;
}

/** 이 인덱스가 "미리 불러온(preload)" 대상인가 — 보이는 항목 자신 포함, 다음 PRELOAD_AHEAD개. */
export function isPreloaded(index: number, activeIndex: number): boolean {
  return index >= activeIndex && index <= activeIndex + PRELOAD_AHEAD;
}

/** IntersectionObserver 항목들 중 "가장 많이 보이는" 하나를 고른다 — 그 영상만 재생한다(R26.2). */
export function pickMostVisibleIndex(
  entries: { index: number; isIntersecting: boolean; intersectionRatio: number }[],
  currentActiveIndex: number,
): number {
  let best = currentActiveIndex;
  let bestRatio = -1;
  for (const entry of entries) {
    if (entry.isIntersecting && entry.intersectionRatio > bestRatio) {
      bestRatio = entry.intersectionRatio;
      best = entry.index;
    }
  }
  return best;
}

/** 피드 로딩 상태 — 화면이 그릴 세 가지뿐이다(로딩/실패/완료). */
export type FeedLoadState =
  | { status: 'loading' }
  | { status: 'error'; message: string; url: string }
  | { status: 'ready'; items: ShortsFeedItem[]; nextCursor: number | null; hasNext: boolean };

/**
 * fetch 결과를 이전 상태에 반영한다(R26.4: 실패→오류 문구, 재시도로 다시 이 함수를 불러 성공하면
 * 복구). 실패해도 이전에 쌓아 둔 items를 날리지 않는다 — "더 불러오기" 실패가 이미 본 영상까지
 * 지우면 안 된다.
 */
export function applyFetchResult(prevItems: ShortsFeedItem[], result: Result<ShortsFeedPage>): FeedLoadState {
  if (!result.ok) {
    return { status: 'error', message: result.error, url: result.url };
  }
  return {
    status: 'ready',
    items: appendPage(prevItems, result.data),
    nextCursor: result.data.nextCursor,
    hasNext: result.data.hasNext,
  };
}
