'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { Result } from '@/lib/api';
import type { ShortsFeedItem, ShortsFeedPage } from '@/lib/contracts';
import {
  applyFetchResult,
  isPreloaded,
  pickMostVisibleIndex,
  SHORTS_FEED_PATH,
  shouldPrefetchNext,
  type FeedLoadState,
} from '@/lib/shortsFeed';
import { gradient, won } from '@/lib/ui';

const PAGE_SIZE = 10;

/**
 * 브라우저에서 부르는 "더 불러오기" — 상대경로라 `next.config.ts`의 `/api/:path*` rewrite가
 * Spring으로 보낸다(서버 컴포넌트의 첫 쪽만 `lib/api.ts`의 `getShortsFeed`로 SPRING_API를 직접 쓴다).
 */
async function fetchFeedPage(params: { cursor?: number; size?: number }): Promise<Result<ShortsFeedPage>> {
  const qs = new URLSearchParams();
  if (params.cursor != null) qs.set('cursor', String(params.cursor));
  if (params.size != null) qs.set('size', String(params.size));
  const url = `${SHORTS_FEED_PATH}${qs.toString() ? `?${qs}` : ''}`;
  try {
    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) return { ok: false, error: `HTTP ${res.status}`, url, status: res.status };
    return { ok: true, data: (await res.json()) as ShortsFeedPage };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e), url };
  }
}

/**
 * 숏폼 피드 화면(R26). 서버 컴포넌트가 첫 쪽을 이미 받아 `initial`로 내려준다 — 그게 실패했어도
 * (R26.4) 여기서 같은 모양으로 다시 시도할 수 있다.
 */
export function ShortsFeed({ initial }: { initial: Result<ShortsFeedPage> }) {
  const [state, setState] = useState<FeedLoadState>(() => applyFetchResult([], initial));
  const [activeIndex, setActiveIndex] = useState(0);
  const loadingMore = useRef(false);
  const cardRefs = useRef(new Map<number, HTMLElement>());

  const retryInitial = useCallback(() => {
    setState({ status: 'loading' });
    fetchFeedPage({ size: PAGE_SIZE }).then((res) => setState((prev) =>
      applyFetchResult(prev.status === 'ready' ? prev.items : [], res)));
  }, []);

  const loadMore = useCallback((cursor: number) => {
    if (loadingMore.current) return;
    loadingMore.current = true;
    fetchFeedPage({ cursor, size: PAGE_SIZE }).then((res) => {
      setState((prev) => applyFetchResult(prev.status === 'ready' ? prev.items : [], res));
      loadingMore.current = false;
    });
  }, []);

  const itemCount = state.status === 'ready' ? state.items.length : 0;

  // 화면에 보이는 영상 1개만 "재생" 상태로 — IntersectionObserver로 가장 많이 보이는 카드를 고른다(R26.2).
  useEffect(() => {
    if (state.status !== 'ready' || itemCount === 0) return;
    const observer = new IntersectionObserver(
      (observerEntries) => {
        const mapped = observerEntries.map((entry) => ({
          index: Number((entry.target as HTMLElement).dataset.index),
          isIntersecting: entry.isIntersecting,
          intersectionRatio: entry.intersectionRatio,
        }));
        setActiveIndex((cur) => pickMostVisibleIndex(mapped, cur));
      },
      { threshold: 0.6 },
    );
    cardRefs.current.forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [state.status, itemCount]);

  // 끝이 가까워지면 다음 쪽을 미리 당겨 받는다(R26.1: "다음 1~2개는 미리 로드").
  useEffect(() => {
    if (state.status !== 'ready') return;
    if (state.nextCursor != null && shouldPrefetchNext(activeIndex, state.items.length, state.hasNext)) {
      loadMore(state.nextCursor);
    }
  }, [activeIndex, state, loadMore]);

  if (state.status === 'loading') {
    return <div className="shorts-empty">불러오는 중…</div>;
  }

  if (state.status === 'error') {
    return (
      <section className="fail">
        <h2 style={{ fontSize: 16 }}>숏폼 피드를 불러오지 못했습니다</h2>
        <p className="muted" style={{ marginTop: 6 }}>
          이 화면은 실패를 숨기지 않는다. 연동 지점이 죽으면 사용자가 아는 상태여야 한다.
        </p>
        <p className="mono" style={{ fontSize: 12.5, margin: '10px 0 0' }}>GET {state.url}</p>
        <p style={{ margin: '6px 0 0' }}>
          <code>{state.message}</code>
        </p>
        <button type="button" className="btn" style={{ marginTop: 12 }} onClick={retryInitial}>
          다시 시도
        </button>
      </section>
    );
  }

  if (state.items.length === 0) {
    return <div className="shorts-empty">아직 올라온 숏폼이 없습니다.</div>;
  }

  return (
    <div className="shorts-scroller">
      {state.items.map((item, index) => (
        <ShortsFeedCard
          key={item.id}
          item={item}
          index={index}
          active={index === activeIndex}
          preloaded={isPreloaded(index, activeIndex)}
          registerRef={(el) => {
            if (el) cardRefs.current.set(index, el);
            else cardRefs.current.delete(index);
          }}
        />
      ))}
      {state.hasNext ? <div className="shorts-loadmore muted mono">다음 영상을 불러오는 중…</div> : null}
    </div>
  );
}

function ShortsFeedCard({
  item,
  index,
  active,
  preloaded,
  registerRef,
}: {
  item: ShortsFeedItem;
  index: number;
  active: boolean;
  preloaded: boolean;
  registerRef: (el: HTMLElement | null) => void;
}) {
  return (
    <section className="shorts-card" ref={registerRef} data-index={index} data-active={active} data-preload={preloaded}>
      {/*
        변환 워커(R21~R24)가 아직 없어 재생 가능한 HLS URL이 없다 — 그래서 실제 <video>가 아니라
        재생 상태를 보여주는 자리다. IntersectionObserver가 고른 "active" 카드만 재생 표시를 하고
        나머지는 일시정지 표시를 한다는 제어 로직 자체는 그대로이므로, 나중에 재생 URL이 생기면
        이 div를 <video src=... autoPlay={active} />로 바꾸기만 하면 된다.
      */}
      <div className="shorts-frame" style={{ background: gradient(String(item.id)) }}>
        <div />
        <div>
          <div className="shorts-frame-state">{active ? '▶ 재생 중' : '❙❙ 일시정지'}</div>
          <div className="shorts-frame-meta mono">
            {item.durationSeconds}s · {item.width}×{item.height}
          </div>
        </div>
        {!active && preloaded ? <div className="shorts-frame-preload mono">미리 불러옴</div> : null}
      </div>
      {item.products.length > 0 ? (
        <div className="shorts-products">
          {item.products.map((p) => (
            <a key={p.productId} className="shorts-product" href={`/product.html?id=${p.productId}`}>
              <span>{p.name}</span>
              <span className="mono">{won(p.price)}</span>
            </a>
          ))}
        </div>
      ) : null}
    </section>
  );
}
