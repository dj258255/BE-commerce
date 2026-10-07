'use client';

import Hls from 'hls.js';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { Result } from '@/lib/api';
import type { ShortsFeedItem, ShortsFeedPage } from '@/lib/contracts';
import {
  applyFetchResult,
  isPreloaded,
  pickHlsPlaybackStrategy,
  pickMostVisibleIndex,
  SHORTS_FEED_PATH,
  shouldPrefetchNext,
  type FeedLoadState,
} from '@/lib/shortsFeed';
import { won } from '@/lib/ui';

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
      <div className="shorts-frame">
        <ShortsVideoPlayer
          src={item.masterPlaylistUrl}
          poster={item.thumbnailUrl}
          active={active}
          preloaded={preloaded}
        />
        <div className="shorts-frame-meta mono">
          {item.durationSeconds}s · {item.width}×{item.height}
        </div>
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

/**
 * HLS 재생(R26) — `active`인 카드만 재생하고 나머지는 멈춘다(R26.2 규칙은 그대로, 이제 실제
 * `<video>`를 움직인다). `preloaded`(현재 카드 포함 다음 {@code PRELOAD_AHEAD}개, R26.1)가 되는
 * 순간에만 소스를 붙여 미리 버퍼링한다 — 화면 밖 카드까지 전부 내려받지 않는다.
 *
 * <p>Safari/iOS처럼 `<video>`가 HLS를 네이티브로 틀 수 있으면 hls.js를 띄우지 않는다
 * ({@link pickHlsPlaybackStrategy}, 순수 로직이라 `lib/shortsFeed.ts`에서 단위 테스트한다).
 * 자동재생은 브라우저 정책상 소리가 있으면 막히므로 `muted`로 둔다.
 */
function ShortsVideoPlayer({
  src,
  poster,
  active,
  preloaded,
}: {
  src: string;
  poster: string;
  active: boolean;
  preloaded: boolean;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const sourceAttached = useRef(false);

  // preloaded(보이는 카드 자신 포함 다음 몇 개)가 되는 첫 순간에만 소스를 붙인다 — 그 뒤로는
  // active가 왔다 갔다 해도(위아래로 다시 스와이프) 다시 붙이지 않는다.
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !preloaded || sourceAttached.current) return;
    sourceAttached.current = true;

    const strategy = pickHlsPlaybackStrategy(video.canPlayType('application/vnd.apple.mpegurl'), Hls.isSupported());
    if (strategy === 'native') {
      video.src = src;
    } else if (strategy === 'hls.js') {
      const hls = new Hls();
      hls.loadSource(src);
      hls.attachMedia(video);
      hlsRef.current = hls;
    }
    // 'unsupported'면 아무것도 붙이지 않는다 — poster만 보인다.

    return () => {
      hlsRef.current?.destroy();
      hlsRef.current = null;
    };
  }, [preloaded, src]);

  // 화면에 보이는 카드만 재생한다(R26.2) — 나머지는 멈춘다.
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    if (active) {
      // 자동재생 거부(브라우저 정책)는 조용히 무시한다 — 사용자가 직접 상호작용하면 다음
      // 활성화 때 다시 시도된다. muted라 대부분 브라우저에서 거부되지 않는다.
      video.play().catch(() => {});
    } else {
      video.pause();
    }
  }, [active]);

  return (
    <video
      ref={videoRef}
      className="shorts-video"
      poster={poster}
      muted
      playsInline
      loop
      preload={preloaded ? 'auto' : 'none'}
    />
  );
}
