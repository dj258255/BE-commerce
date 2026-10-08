'use client';

import Hls from 'hls.js';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  estimatePlaybackWallClockMs,
  INITIAL_LIVE_PIN_SYNC_STATE,
  receiveLivePinEvent,
  tickLivePinSync,
  type LivePinEvent,
  type LivePinSyncState,
} from '@/lib/livePin';
import { won } from '@/lib/ui';

type PlaybackInfo = { id: number; status: 'SCHEDULED' | 'LIVE' | 'ENDED'; hlsUrl: string | null };

type PlaybackState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; data: PlaybackInfo };

/**
 * 라이브 방송 시청 화면(R8·R9) — `/live/{id}`. hls.js로 MediaMTX HLS를 틀고, WebSocket으로
 * 받는 고정 이벤트를 영상 재생 시점과 맞춰 카드로 겹쳐 보여준다.
 *
 * <p>재생 시점↔서버 시각 동기화는 HLS의 `#EXT-X-PROGRAM-DATE-TIME`을 쓴다 — hls.js가
 * {@code FRAG_CHANGED}에서 주는 그 프래그먼트의 벽시계 시작 시각과, 그 프래그먼트 안에서
 * 지금 재생 위치가 얼마나 지났는지(`currentTime - frag.start`)를 더해 "지금 화면에 보이는
 * 장면의 실제 시각"을 추정한다({@link estimatePlaybackWallClockMs}, 근거는 ADR-084). 이
 * 추정치가 이벤트의 {@code effectiveAt}에 도달한 뒤에만 카드를 바꾼다({@link tickLivePinSync}).
 */
export function LiveViewer({ broadcastId }: { broadcastId: number }) {
  const [playback, setPlayback] = useState<PlaybackState>({ status: 'loading' });
  const [pinState, setPinState] = useState<LivePinSyncState>(INITIAL_LIVE_PIN_SYNC_STATE);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const currentFragment = useRef<{ programDateTimeMs: number; startSeconds: number } | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch(`/api/v1/live/broadcasts/${broadcastId}/playback`, { cache: 'no-store' })
      .then(async (res) => {
        if (cancelled) return;
        if (!res.ok) {
          setPlayback({ status: 'error', message: `HTTP ${res.status}` });
          return;
        }
        setPlayback({ status: 'ready', data: (await res.json()) as PlaybackInfo });
      })
      .catch((e) => {
        if (!cancelled) setPlayback({ status: 'error', message: e instanceof Error ? e.message : String(e) });
      });
    return () => {
      cancelled = true;
    };
  }, [broadcastId]);

  // 고정 이벤트 WebSocket(R9) — same-origin 상대 경로를 쓴다(next.config.ts의 /api/:path*
  // rewrite가 commerce로 보낸다). 연결 즉시 현재 스냅샷을 받는다(R9.2, 서버가 보장).
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${window.location.host}/api/v1/live/broadcasts/${broadcastId}/pins/ws`);
    ws.onmessage = (ev) => {
      try {
        const event = JSON.parse(ev.data as string) as LivePinEvent;
        setPinState((prev) => receiveLivePinEvent(prev, event));
      } catch {
        // 깨진 메시지는 조용히 버린다 — 다음 메시지(또는 재연결 스냅샷)가 다시 맞춰 준다.
      }
    };
    return () => ws.close();
  }, [broadcastId]);

  // hls.js 연결 — 재생 URL이 준비되면 붙인다(R26 ShortsVideoPlayer와 같은 네이티브/hls.js 분기).
  useEffect(() => {
    if (playback.status !== 'ready' || !playback.data.hlsUrl) return;
    const video = videoRef.current;
    if (!video) return;
    const src = playback.data.hlsUrl;

    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src;
    } else if (Hls.isSupported()) {
      const hls = new Hls();
      hls.on(Hls.Events.FRAG_CHANGED, (_event, data) => {
        if (data.frag.programDateTime != null) {
          currentFragment.current = { programDateTimeMs: data.frag.programDateTime, startSeconds: data.frag.start };
        }
      });
      hls.loadSource(src);
      hls.attachMedia(video);
      hlsRef.current = hls;
    }
    return () => {
      hlsRef.current?.destroy();
      hlsRef.current = null;
    };
  }, [playback]);

  const handleTimeUpdate = useCallback(() => {
    const video = videoRef.current;
    const fragment = currentFragment.current;
    if (!video || !fragment) return;
    const estimatedWallClockMs = estimatePlaybackWallClockMs(
      fragment.programDateTimeMs,
      fragment.startSeconds,
      video.currentTime,
    );
    setPinState((prev) => tickLivePinSync(prev, estimatedWallClockMs));
  }, []);

  if (playback.status === 'loading') {
    return <div className="shorts-empty">불러오는 중…</div>;
  }
  if (playback.status === 'error') {
    return (
      <section className="fail">
        <h2 style={{ fontSize: 16 }}>시청 화면을 불러오지 못했습니다</h2>
        <p className="mono" style={{ fontSize: 12.5 }}>{playback.message}</p>
      </section>
    );
  }

  return (
    <div className="shorts-frame" style={{ position: 'relative' }}>
      {playback.data.hlsUrl ? (
        <video
          ref={videoRef}
          className="shorts-video"
          muted
          playsInline
          controls
          onTimeUpdate={handleTimeUpdate}
        />
      ) : (
        <div className="shorts-empty">
          {playback.data.status === 'ENDED' ? '방송이 끝났습니다.' : '방송 준비 중입니다.'}
        </div>
      )}
      {pinState.card ? (
        <div className="shorts-product" style={{ position: 'absolute', bottom: 16, left: 16, right: 16 }}>
          <span>{pinState.card.productName}</span>
          <span className="mono">
            {won(pinState.card.price)} · 남은 수량 {pinState.card.remainingQuantity}
          </span>
        </div>
      ) : null}
    </div>
  );
}
