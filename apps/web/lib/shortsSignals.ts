/**
 * 숏폼 시청 신호(R27) — DOM·네트워크를 모르는 순수 로직. 컴포넌트(`ShortsFeed`)는 `<video>`의
 * timeupdate·활성 전환 시점의 숫자만 넘기고, 이 파일이 시청시간·완료·다시보기·건너뛰기를
 * 집계한다(`lib/shortsFeed.ts`가 피드 조립을 DOM 밖에서 테스트하는 것과 같은 이유).
 */

/** 서버(`POST /api/v1/shorts/{id}/signals`)에 보낼 payload — 백엔드 `RecordViewSignalRequest`와 같은 모양. */
export type ViewSignalPayload = {
  watchSeconds: number;
  completed: boolean;
  replayCount: number;
  skippedWithin3s: boolean;
  productTagTapped: boolean;
  anonymousId: string | null;
};

/** 영상 하나를 보기 시작한 순간부터 쌓이는 누적 상태. */
export type ViewSession = {
  videoId: number;
  startedAtMs: number;
  completed: boolean;
  replayCount: number;
  productTagTapped: boolean;
  lastTimeSeconds: number;
};

export function startViewSession(videoId: number, nowMs: number): ViewSession {
  return { videoId, startedAtMs: nowMs, completed: false, replayCount: 0, productTagTapped: false, lastTimeSeconds: 0 };
}

/**
 * `<video>`의 timeupdate에서 호출한다. 피드가 `loop`를 쓰므로 'ended'가 뜨지 않는다 — 재생
 * 위치가 영상 끝 근처(<1초 남음)였다가 다시 처음 근처(<1초)로 떨어지면 "끝까지 보고 되감겼다"로
 * 본다. 첫 번째 완결은 `completed`, 그 이후의 같은 일은 `replayCount`에 쌓인다(R27: 완료 여부와
 * 다시 보기 횟수는 서로 다른 신호다).
 */
export function onTimeUpdate(session: ViewSession, currentTimeSeconds: number, durationSeconds: number): ViewSession {
  const wrapped = session.lastTimeSeconds >= durationSeconds - 1 && currentTimeSeconds < 1;
  if (!wrapped) {
    return { ...session, lastTimeSeconds: currentTimeSeconds };
  }
  return {
    ...session,
    lastTimeSeconds: currentTimeSeconds,
    completed: true,
    replayCount: session.completed ? session.replayCount + 1 : session.replayCount,
  };
}

/** 그 시청 동안 상품 카드를 눌렀다(R27) — 영상당 한 번만 세면 되므로 불린만 켠다. */
export function onProductTagTap(session: ViewSession): ViewSession {
  return { ...session, productTagTapped: true };
}

/**
 * 영상이 비활성화되거나(다음으로 스와이프) 화면을 떠날 때 호출 — 누적 상태를 서버로 보낼
 * payload로 굳힌다. `skippedWithin3s`(R27)는 완료하지 못한 채 3초 미만만 보고 넘어간 경우다.
 */
export function finalizeViewSession(
  session: ViewSession,
  endedAtMs: number,
  anonymousId: string | null,
): ViewSignalPayload {
  const watchSeconds = Math.max(0, Math.round((endedAtMs - session.startedAtMs) / 1000));
  return {
    watchSeconds,
    completed: session.completed,
    replayCount: session.replayCount,
    skippedWithin3s: !session.completed && watchSeconds < 3,
    productTagTapped: session.productTagTapped,
    anonymousId,
  };
}

/** 시청 신호 전송 경로(R27). */
export function shortsSignalPath(videoId: number): string {
  return `/api/v1/shorts/${videoId}/signals`;
}

/** localStorage처럼 문자열 하나를 읽고 쓰는 최소 인터페이스 — 테스트는 메모리 구현을 넣는다. */
export interface IdStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

const ANONYMOUS_ID_KEY = 'shorts-anonymous-id';

/**
 * 비로그인 시청자의 익명 식별자(R27: "비로그인은 익명 식별자"). 저장된 값이 있으면 그대로
 * 쓰고(재방문 묶음을 위해), 없으면 `generateId`로 만들어 저장한다.
 */
export function getOrCreateAnonymousId(storage: IdStorage, generateId: () => string): string {
  const existing = storage.getItem(ANONYMOUS_ID_KEY);
  if (existing) return existing;
  const created = generateId();
  storage.setItem(ANONYMOUS_ID_KEY, created);
  return created;
}
