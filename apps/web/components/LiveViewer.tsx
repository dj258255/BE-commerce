'use client';

import Hls from 'hls.js';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import {
  estimatePlaybackWallClockMs,
  INITIAL_LIVE_PIN_SYNC_STATE,
  receiveLivePinEvent,
  tickLivePinSync,
  type LivePinEvent,
  type LivePinSyncState,
  type PinCard,
} from '@/lib/livePin';
import {
  applyOrderResult,
  applyPaymentResult,
  canPlaceOrder,
  INITIAL_LIVE_ORDER_STATE,
  type LiveOrderState,
} from '@/lib/liveOrder';
import { loginFailureMessage, validateLoginForm } from '@/lib/liveLogin';
import { won } from '@/lib/ui';

/** 브라우저 세션 동안만 토큰을 들고 있는다 — 탭을 닫으면 사라진다(R5와 같은 수준, 비밀 저장소가 아니다). */
const AUTH_TOKEN_STORAGE_KEY = 'live-auth-token';

async function readJsonSafely(res: Response): Promise<Record<string, unknown> | null> {
  try {
    return (await res.json()) as Record<string, unknown>;
  } catch {
    return null;
  }
}

type PlaybackInfo = { id: number; status: 'SCHEDULED' | 'LIVE' | 'ENDED'; hlsUrl: string | null; title?: string };

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

  // 로그인(R5) — 주문·결제에만 필요하다. 시청 자체는 토큰이 없어도 위의 재생·WebSocket이 그대로 된다.
  const [token, setToken] = useState<string | null>(null);
  const [loginForm, setLoginForm] = useState({ username: '', password: '' });
  const [loginBusy, setLoginBusy] = useState(false);
  const [loginError, setLoginError] = useState<string | null>(null);

  // 고정 상품 카드 "바로 주문"(R11) 진행 상태.
  const [orderState, setOrderState] = useState<LiveOrderState>(INITIAL_LIVE_ORDER_STATE);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const saved = window.sessionStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
    if (saved) setToken(saved);
  }, []);

  const handleLogin = useCallback(async (e: FormEvent) => {
    e.preventDefault();
    const validationError = validateLoginForm(loginForm);
    if (validationError) {
      setLoginError(validationError);
      return;
    }
    setLoginBusy(true);
    setLoginError(null);
    try {
      const res = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(loginForm),
      });
      const body = await readJsonSafely(res);
      if (!res.ok) {
        setLoginError(loginFailureMessage(res.status));
        return;
      }
      const newToken = body?.token as string | undefined;
      if (!newToken) {
        setLoginError('로그인 응답에 토큰이 없습니다.');
        return;
      }
      setToken(newToken);
      window.sessionStorage.setItem(AUTH_TOKEN_STORAGE_KEY, newToken);
    } catch (e) {
      setLoginError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoginBusy(false);
    }
  }, [loginForm]);

  // R11.1: 고정 상품 카드의 "바로 주문" — 장바구니를 거치지 않고 기존 주문 API를 바로 부른다.
  // 가격·수량은 요청에 싣지 않는다(R10) — 서버가 지금 고정 상태로 판정한다.
  const placeOrder = useCallback(async (card: PinCard) => {
    setOrderState({ phase: 'placing' });
    try {
      const res = await fetch(`/api/v1/live/broadcasts/${broadcastId}/orders`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': crypto.randomUUID(),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ productId: card.productId }),
      });
      const body = await readJsonSafely(res);
      setOrderState(applyOrderResult({ status: res.status, body }));
    } catch (e) {
      setOrderState({ phase: 'rejected', reason: e instanceof Error ? e.message : String(e) });
    }
  }, [broadcastId, token]);

  // R11.1·R11.2: 기존 결제 승인 API로 이어간다(장바구니 없이, 주문 생성에서 받은 금액 그대로).
  // 결제 실패(400 등) 뒤 재시도도 이 함수를 다시 부른다 — 새 Idempotency-Key로 새 승인 시도가 된다.
  const confirmPayment = useCallback(async (orderNo: string, totalAmount: number) => {
    setOrderState({ phase: 'confirming', orderNo, totalAmount });
    try {
      const res = await fetch('/api/v1/payments/confirm', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': crypto.randomUUID(),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({
          paymentKey: `live-${orderNo}-${crypto.randomUUID()}`,
          orderNo,
          amount: totalAmount,
          pointAmount: 0,
          walletAmount: 0,
          installmentMonths: 0,
        }),
      });
      const body = await readJsonSafely(res);
      setOrderState(applyPaymentResult(orderNo, totalAmount, { status: res.status, body }));
    } catch (e) {
      setOrderState({ phase: 'paymentFailed', orderNo, totalAmount, reason: e instanceof Error ? e.message : String(e) });
    }
  }, [token]);

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

  // R8·R9.2: 영상이 아직 재생되지 않아도(SCHEDULED 방송, 버퍼링, 자동재생 차단 등) 고정 카드는
  // 바로 보여야 한다(R8 인수 조건 — "표시된다"가 영상 재생에 달려 있지 않다). 실제 프래그먼트
  // 재생 시점(FRAG_CHANGED)이 아직 없을 때만 실 서버 시각으로 대신 틱한다 — 재생이 시작되면
  // handleTimeUpdate의 프레임 기반 추정이 넘겨받는다(currentFragment가 채워지는 즉시 이 틱은
  // 아무것도 하지 않게 된다, R9.1).
  useEffect(() => {
    const interval = window.setInterval(() => {
      if (currentFragment.current != null) return;
      setPinState((prev) => tickLivePinSync(prev, Date.now()));
    }, 500);
    return () => window.clearInterval(interval);
  }, []);

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
    <div className="live-page">
      {/* 어떤 방송을 보고 있는지 알 수 있게 제목을 보여준다. */}
      <h1 className="live-title">{playback.data.title || `방송 #${playback.data.id}`}</h1>
      <div className="live-frame">
        {playback.data.hlsUrl ? (
          <video
            ref={videoRef}
            className="live-video"
            muted
            playsInline
            controls
            onTimeUpdate={handleTimeUpdate}
          />
        ) : (
          <div className="live-empty">
            {playback.data.status === 'ENDED' ? '방송이 끝났습니다.' : '방송 준비 중입니다.'}
          </div>
        )}
        {pinState.card ? (
          <div className="shorts-product" style={{ position: 'absolute', bottom: 16, left: 16, right: 16 }}>
            <span>{pinState.card.productName}</span>
            <span className="mono">
              {won(pinState.card.price)} ·{' '}
              {/* R14: 매진은 재생 시점을 기다리지 않고 즉시 이 표시로 바뀐다(QUANTITY_CHANGED, lib/livePin.ts) */}
              {pinState.card.remainingQuantity > 0 ? `남은 수량 ${pinState.card.remainingQuantity}` : '매진'}
            </span>
          </div>
        ) : null}
      </div>

      {/* R5: 시청 자체는 비로그인도 되지만 주문은 로그인이 필요하다 — 안내를 항상 보여준다. */}
      {!token ? (
        <form onSubmit={handleLogin} className="notice" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="muted" style={{ fontSize: 12.5 }}>주문하려면 로그인하세요</span>
          <input
            value={loginForm.username}
            onChange={(e) => setLoginForm((f) => ({ ...f, username: e.target.value }))}
            placeholder="아이디"
            className="mono"
            style={{ width: 90, padding: '5px 8px', border: '1px solid var(--line2)', borderRadius: 7 }}
          />
          <input
            value={loginForm.password}
            onChange={(e) => setLoginForm((f) => ({ ...f, password: e.target.value }))}
            placeholder="비밀번호"
            type="password"
            className="mono"
            style={{ width: 120, padding: '5px 8px', border: '1px solid var(--line2)', borderRadius: 7 }}
          />
          <button type="submit" className="btn" disabled={loginBusy}>
            {loginBusy ? '로그인 중…' : '로그인'}
          </button>
          {loginError ? <span style={{ color: 'var(--bad)', fontSize: 12.5 }}>{loginError}</span> : null}
        </form>
      ) : null}

      {pinState.card ? (
        <LiveOrderPanel
          card={pinState.card}
          state={orderState}
          token={token}
          onPlaceOrder={placeOrder}
          onConfirmPayment={confirmPayment}
        />
      ) : null}
    </div>
  );
}

/**
 * 고정 상품 카드의 주문·결제 진행 영역(R11) — 단계별로 다른 조작을 보여준다: 바로 주문 →
 * (생성되면) 결제하기 → 완료/실패(R11.2 실패 사유+재시도)/거절(R10.2·R14.2)/로그인 필요(R5.2).
 */
function LiveOrderPanel({
  card,
  state,
  token,
  onPlaceOrder,
  onConfirmPayment,
}: {
  card: PinCard;
  state: LiveOrderState;
  token: string | null;
  onPlaceOrder: (card: PinCard) => void;
  onConfirmPayment: (orderNo: string, totalAmount: number) => void;
}) {
  const orderButtonEnabled = canPlaceOrder(card, state);

  return (
    <div className="notice">
      {state.phase === 'readyToPay' ? (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <span>주문이 생성됐습니다(주문번호 {state.orderNo}, {won(state.totalAmount)})</span>
          <button type="button" className="btn" onClick={() => onConfirmPayment(state.orderNo, state.totalAmount)}>
            결제하기
          </button>
        </div>
      ) : state.phase === 'confirming' ? (
        <span className="muted">결제 승인 중…</span>
      ) : state.phase === 'pending' ? (
        <span className="muted">
          결제 결과를 아직 확인하지 못했습니다(UNKNOWN) — 복구 처리가 끝나면 자동으로 확정됩니다.
        </span>
      ) : state.phase === 'paid' ? (
        <div className="notice ok" style={{ border: 0, padding: 0, background: 'transparent' }}>
          결제가 완료됐습니다(주문번호 {state.orderNo}).
        </div>
      ) : (
        <>
          {/* 버튼을 눌러 보기 전에도 바로 곁에서 로그인이 필요함을 알린다 — 위쪽 로그인 줄과
              떨어져 있던 탓에 누르기 전까지 모르는 문제(진행 중 지시)를 고친다. */}
          {!token ? (
            <div className="notice warn" style={{ marginBottom: 8, border: 0, padding: 0, background: 'transparent' }}>
              로그인해야 주문할 수 있습니다.
            </div>
          ) : null}
          {/* R11.2: 결제 실패 사유 + 재시도. 주문은 이미 생성돼 있으므로 같은 orderNo로 다시 승인만 시도한다. */}
          {state.phase === 'paymentFailed' ? (
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
              <span style={{ color: 'var(--bad)' }}>결제에 실패했습니다: {state.reason}</span>
              <button type="button" className="btn" onClick={() => onConfirmPayment(state.orderNo, state.totalAmount)}>
                재시도
              </button>
            </div>
          ) : null}
          {state.phase === 'rejected' ? (
            <div style={{ color: 'var(--bad)', marginBottom: 8 }}>{state.reason}</div>
          ) : null}
          <button
            type="button"
            className="btn"
            disabled={!orderButtonEnabled}
            onClick={() => onPlaceOrder(card)}
          >
            {/* R14.1: 매진이거나 고정이 풀리면(카드 자체가 없어 이 컴포넌트가 안 보이거나
                remainingQuantity가 0이면) 비활성화된다 — canPlaceOrder가 그 둘을 함께 본다. */}
            {state.phase === 'placing' ? '주문 생성 중…' : card.remainingQuantity > 0 ? '바로 주문' : '매진'}
          </button>
        </>
      )}
    </div>
  );
}
